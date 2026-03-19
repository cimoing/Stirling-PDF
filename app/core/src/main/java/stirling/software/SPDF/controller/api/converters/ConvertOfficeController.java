package stirling.software.SPDF.controller.api.converters;

import java.io.File;

import org.apache.commons.io.FileUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import stirling.software.SPDF.service.FormatConvertService;
import stirling.software.common.annotations.AutoJobPostMapping;
import stirling.software.common.annotations.api.ConvertApi;
import stirling.software.common.model.api.GeneralFile;
import stirling.software.common.service.CustomPDFDocumentFactory;
import stirling.software.common.util.ExceptionUtils;
import stirling.software.common.util.GeneralUtils;
import stirling.software.common.util.WebResponseUtils;

@ConvertApi
@RequiredArgsConstructor
@Slf4j
public class ConvertOfficeController {

    private final CustomPDFDocumentFactory pdfDocumentFactory;
    private final FormatConvertService formatConvertService;

    @AutoJobPostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, value = "/file/pdf")
    @Operation(
            summary = "Convert a file to a PDF",
            description =
                    "This endpoint converts a given file to a PDF using the configured conversion service. Input:ANY Output:PDF Type:SISO")
    public ResponseEntity<byte[]> processFileToPDF(@ModelAttribute GeneralFile generalFile)
            throws Exception {
        MultipartFile inputFile = generalFile.getFileInput();
        File file = null;
        try {
            if (formatConvertService == null || !formatConvertService.isEnabled()) {
                throw ExceptionUtils.createRuntimeException(
                        "error.dependencyMissing",
                        "File-to-PDF conversion requires external conversion service (FormatConvert) to be enabled.",
                        null);
            }
            file = formatConvertService.convertToPdf(inputFile);

            try (PDDocument doc = pdfDocumentFactory.load(file)) {
                return WebResponseUtils.pdfDocToWebResponse(
                        doc,
                        GeneralUtils.generateFilename(
                                inputFile.getOriginalFilename(), "_convertedToPDF.pdf"));
            }
        } finally {
            if (file != null && file.getParent() != null) {
                FileUtils.deleteDirectory(file.getParentFile());
            }
        }
    }
}
