/*
 * Created on 06 dic 2016
 * Copyright 2015 by Andrea Vacondio (andrea.vacondio@gmail.com).
 * This file is part of Sejda.
 *
 * Sejda is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Sejda is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Sejda.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.sejda.impl.sambox.ocr;

import static java.util.Optional.ofNullable;
import static org.sejda.common.ComponentsUtility.nullSafeCloseQuietly;
import static org.sejda.core.notification.dsl.ApplicationEventsNotifier.notifyEvent;
import static org.sejda.core.support.io.IOUtils.createTemporaryBuffer;
import static org.sejda.core.support.io.model.FileOutput.file;
import static org.sejda.core.support.prefix.NameGenerator.nameGenerator;
import static org.sejda.core.support.prefix.model.NameGenerationRequest.nameRequest;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import org.sejda.core.support.io.MultipleOutputWriter;
import org.sejda.core.support.io.OutputWriters;
import org.sejda.impl.sambox.component.DefaultPdfSourceOpener;
import org.sejda.impl.sambox.component.PDDocumentHandler;
import org.sejda.impl.sambox.ocr.component.OCR;
import org.sejda.impl.sambox.ocr.component.OcrTextExtractor;
import org.sejda.model.SejdaFileExtensions;
import org.sejda.model.exception.TaskException;
import org.sejda.model.exception.TaskExecutionException;
import org.sejda.model.input.PdfSource;
import org.sejda.model.input.PdfSourceOpener;
import org.sejda.model.parameter.OcrTextByPagesParameters;
import org.sejda.model.pdf.encryption.PdfAccessPermission;
import org.sejda.model.task.BaseTask;
import org.sejda.model.task.TaskExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tesseract/SAMBox implementation of a task extracting text performing OCR on a {@link PdfSource} and producing an output text file per page
 * 
 * @author Andrea Vacondio
 */
public class OcrTextByPagesTask extends BaseTask<OcrTextByPagesParameters> {

    private static final Logger LOG = LoggerFactory.getLogger(OcrTextByPagesTask.class);

    private PDDocumentHandler documentHandler = null;
    private MultipleOutputWriter outputWriter;
    private PdfSourceOpener<PDDocumentHandler> documentLoader;

    @Override
    public void before(OcrTextByPagesParameters parameters, TaskExecutionContext executionContext)
            throws TaskException {
        super.before(parameters, executionContext);
        documentLoader = new DefaultPdfSourceOpener();
        outputWriter = OutputWriters.newMultipleOutputWriter(parameters.getExistingOutputPolicy(), executionContext);
    }

    @Override
    public void execute(OcrTextByPagesParameters parameters) throws TaskException {
        PdfSource<?> source = parameters.getSource();
        LOG.debug("Opening {}", source);
        documentHandler = source.open(documentLoader);
        documentHandler.getPermissions().ensurePermission(PdfAccessPermission.COPY_AND_EXTRACT);

        documentHandler.setCompress(parameters.isCompress());
        documentHandler.setVersionOnPDDocument(parameters.getVersion());

        Set<Integer> pages = parameters.getPages(documentHandler.getNumberOfPages());
        if (pages == null || pages.isEmpty()) {
            throw new TaskExecutionException("No page has been selected for extraction.");
        }

        int currentStep = 0;
        int totalSteps = pages.size();

        for (Integer current : pages) {
            executionContext().assertTaskNotCancelled();
            currentStep++;
            LOG.debug("Extracting text from page {}", current);

            File tmpFile = createTemporaryBuffer();
            LOG.debug("Created output on temporary buffer {}", tmpFile);
            // add the language if the doc has one set
            ofNullable(documentHandler.getUnderlyingPDDocument().getDocumentCatalog().getLanguage())
                    .map(l -> new Locale.Builder().setLanguageTag(l).build()).filter(Objects::nonNull)
                    .ifPresent(parameters::addLanguage);

            try (OcrTextExtractor ocrExtractor = new OcrTextExtractor(
                    Files.newBufferedWriter(tmpFile.toPath(), Charset.forName(parameters.getTextEncoding())),
                    new OCR())) {
                ocrExtractor.setLanguage(parameters.getLanguages());
                ocrExtractor.accept(documentHandler.getPage(current));
            } catch (IOException e) {
                throw new TaskExecutionException("An error occurred creating a file writer", e);
            } catch (UnsatisfiedLinkError err) {
                throw new TaskExecutionException("Unable to find Tesseract native libraries", err);
            }

            String outName = nameGenerator(parameters.getOutputPrefix())
                    .generate(nameRequest(SejdaFileExtensions.TXT_EXTENSION).page(current)
                            .originalName(parameters.getSource().getName()).fileNumber(currentStep));
            outputWriter.addOutput(file(tmpFile).name(outName));
            // close resource
            notifyEvent(executionContext().notifiableTaskMetadata()).stepsCompleted(currentStep).outOf(totalSteps);
        }

        parameters.getOutput().accept(outputWriter);
        LOG.debug("OCR performed, text extracted and written to {}", parameters.getOutput());

    }

    @Override
    public void after() {
        nullSafeCloseQuietly(documentHandler);
    }

}
