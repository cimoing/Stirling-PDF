package stirling.software.SPDF.controller.api.misc;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.multipart.MultipartFile;

import io.github.pixee.security.Filenames;
import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import stirling.software.SPDF.model.api.misc.ProcessPdfWithOcrRequest;
import stirling.software.SPDF.service.FormatConvertService;
import stirling.software.common.annotations.AutoJobPostMapping;
import stirling.software.common.annotations.api.MiscApi;
import stirling.software.common.util.ExceptionUtils;
import stirling.software.common.util.GeneralUtils;
import stirling.software.common.util.TempFile;
import stirling.software.common.util.TempFileManager;
import stirling.software.common.util.WebResponseUtils;

@MiscApi
@Slf4j
@RequiredArgsConstructor
public class OCRController {

    private final TempFileManager tempFileManager;
    private final FormatConvertService formatConvertService;

    @AutoJobPostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, value = "/ocr-pdf")
    @Operation(
            summary = "Process a PDF file with OCR",
            description =
                    "This endpoint processes a PDF file using OCR (Optical Character Recognition). Users can"
                            + " specify languages, sidecar, deskew, clean, cleanFinal, ocrType, ocrRenderType,"
                            + " and removeImagesAfter options. Uses OCRmyPDF if available, falls back to"
                            + " Tesseract. Input:PDF Output:PDF Type:SI-Conditional")
    public ResponseEntity<byte[]> processPdfWithOCR(
            @ModelAttribute ProcessPdfWithOcrRequest request)
            throws IOException, InterruptedException {
        MultipartFile inputFile = request.getFileInput();
        List<String> selectedLanguages = request.getLanguages();
        Boolean sidecar = request.isSidecar();
        String ocrRenderType = request.getOcrRenderType();
        if (selectedLanguages == null || selectedLanguages.isEmpty()) {
            throw ExceptionUtils.createOcrLanguageRequiredException();
        }
        if (!"hocr".equals(ocrRenderType) && !"sandwich".equals(ocrRenderType)) {
            throw ExceptionUtils.createOcrInvalidRenderTypeException();
        }

        // Use try-with-resources for proper temp file management
        try (TempFile tempInputFile = new TempFile(tempFileManager, ".pdf");
                TempFile tempOutputFile = new TempFile(tempFileManager, ".pdf");
                TempFile sidecarTextFile = sidecar ? new TempFile(tempFileManager, ".txt") : null) {

            inputFile.transferTo(tempInputFile.getFile());

            // Local OCR tools (Tesseract/OCRmyPDF) removed; OCR is provided by remote
            // FormatConvert.
            if (formatConvertService == null || !formatConvertService.isEnabled()) {
                throw ExceptionUtils.createOcrToolsUnavailableException();
            }

            try {
                log.info("Running OCR via FormatConvert PDF->DOCX->PDF pipeline (remote)");
                File ocrPdf =
                        formatConvertService.ocrPdfViaFormatConvert(
                                tempInputFile.getFile(), inputFile.getOriginalFilename());
                Files.copy(
                        ocrPdf.toPath(),
                        tempOutputFile.getPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                log.info("FormatConvert OCR pipeline completed successfully");
            } catch (Exception ex) {
                log.error("FormatConvert OCR pipeline failed", ex);
                throw new IOException("Remote OCR (FormatConvert) failed", ex);
            }

            // Read the processed PDF file
            byte[] pdfBytes = Files.readAllBytes(tempOutputFile.getPath());

            // Return the OCR processed PDF as a response
            String outputFilename =
                    GeneralUtils.removeExtension(
                                    Filenames.toSimpleFileName(inputFile.getOriginalFilename()))
                            + "_OCR.pdf";

            if (sidecar && sidecarTextFile != null) {
                // Create a zip file containing both the PDF and the text file
                String outputZipFilename =
                        GeneralUtils.removeExtension(
                                        Filenames.toSimpleFileName(inputFile.getOriginalFilename()))
                                + "_OCR.zip";

                try (TempFile tempZipFile = new TempFile(tempFileManager, ".zip");
                        ZipOutputStream zipOut =
                                new ZipOutputStream(Files.newOutputStream(tempZipFile.getPath()))) {

                    // Add PDF file to the zip
                    ZipEntry pdfEntry = new ZipEntry(outputFilename);
                    zipOut.putNextEntry(pdfEntry);
                    zipOut.write(pdfBytes);
                    zipOut.closeEntry();

                    // Add text file to the zip
                    ZipEntry txtEntry = new ZipEntry(outputFilename.replace(".pdf", ".txt"));
                    zipOut.putNextEntry(txtEntry);
                    Files.copy(sidecarTextFile.getPath(), zipOut);
                    zipOut.closeEntry();

                    zipOut.finish();

                    byte[] zipBytes = Files.readAllBytes(tempZipFile.getPath());

                    // Return the zip file containing both the PDF and the text file
                    return WebResponseUtils.bytesToWebResponse(
                            zipBytes, outputZipFilename, MediaType.APPLICATION_OCTET_STREAM);
                }
            } else {
                // Return the OCR processed PDF as a response
                return WebResponseUtils.bytesToWebResponse(pdfBytes, outputFilename);
            }
        }
    }
}
