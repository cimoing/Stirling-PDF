package stirling.software.SPDF.service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.commons.io.FilenameUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import stirling.software.common.model.ApplicationProperties;

@Service
@RequiredArgsConstructor
@Slf4j
public class FormatConvertService {

    private final ApplicationProperties applicationProperties;

    private final RestTemplate restTemplate = new RestTemplate();

    public boolean isEnabled() {
        ApplicationProperties.FormatConvert cfg = applicationProperties.getFormatConvert();
        return cfg != null
                && cfg.isEnabled()
                && cfg.getBaseUrl() != null
                && !cfg.getBaseUrl().isBlank();
    }

    /** Submit a conversion task and return its task ID. */
    public String submitTask(
            File inputFile, String originalFilename, String sourceFormat, String targetFormat)
            throws IOException {
        if (!isEnabled()) {
            throw new IllegalStateException(
                    "FormatConvert service is not enabled or baseUrl is not configured");
        }

        ApplicationProperties.FormatConvert cfg = applicationProperties.getFormatConvert();
        String baseUrl = cfg.getBaseUrl().replaceAll("/+$", "");
        String url = baseUrl + "/convert/" + sourceFormat + "/to/" + targetFormat;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        if (cfg.getApiKey() != null && !cfg.getApiKey().isBlank()) {
            headers.set("Authorization", "Bearer " + cfg.getApiKey());
        }

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        partHeaders.setContentDispositionFormData(
                "file", originalFilename != null ? originalFilename : "file");
        body.add("file", new HttpEntity<>(new FileSystemResource(inputFile), partHeaders));

        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        log.info("Submitting FormatConvert task to {}", url);
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = restTemplate.postForObject(url, requestEntity, Map.class);
        if (resp == null) {
            throw new IOException("FormatConvert submit returned null body");
        }
        Object codeObj = resp.get("code");
        int code = codeObj instanceof Number ? ((Number) codeObj).intValue() : 0;
        if (code != 0) {
            throw new IOException("FormatConvert submit failed: " + resp.get("message"));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        if (data == null || data.get("id") == null) {
            throw new IOException("FormatConvert submit missing task id");
        }
        String taskId = data.get("id").toString();
        log.info(
                "FormatConvert task submitted: taskId={}, sourceFormat={}, targetFormat={}, originalFilename={}",
                taskId,
                sourceFormat,
                targetFormat,
                originalFilename);
        return taskId;
    }

    /** Poll task status until completed and return output_url. */
    public String waitForOutputUrl(String taskId) throws IOException, InterruptedException {
        ApplicationProperties.FormatConvert cfg = applicationProperties.getFormatConvert();
        String baseUrl = cfg.getBaseUrl().replaceAll("/+$", "");
        String url = baseUrl + "/convert/tasks/" + taskId;

        long timeoutMillis = cfg.getTimeoutSeconds() * 1000L;
        long pollInterval = cfg.getPollIntervalMillis();
        long deadline = System.currentTimeMillis() + timeoutMillis;

        while (System.currentTimeMillis() < deadline) {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = restTemplate.getForObject(url, Map.class);
            if (resp == null) {
                throw new IOException("FormatConvert status returned null body");
            }
            Object codeObj = resp.get("code");
            int code = codeObj instanceof Number ? ((Number) codeObj).intValue() : 0;
            if (code != 0) {
                throw new IOException("FormatConvert task failed: " + resp.get("message"));
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) resp.get("data");
            if (data == null) {
                throw new IOException("FormatConvert status missing data");
            }
            String status = data.get("status") != null ? data.get("status").toString() : null;
            Object outputUrl = data.get("output_url");
            Object errorMessage = data.get("error_message");
            log.info(
                    "FormatConvert task status: taskId={}, status={}, outputUrlPresent={}, errorMessage={}",
                    taskId,
                    status,
                    outputUrl != null && !outputUrl.toString().isBlank(),
                    errorMessage);
            if ("completed".equalsIgnoreCase(status)) {
                Object urlObj = data.get("output_url");
                if (urlObj == null || urlObj.toString().isBlank()) {
                    throw new IOException("FormatConvert completed but output_url is missing");
                }
                log.info("FormatConvert task completed: taskId={}, outputUrl={}", taskId, urlObj);
                return urlObj.toString();
            }
            if ("failed".equalsIgnoreCase(status)) {
                Object err = data.get("error_message");
                log.error("FormatConvert task failed: taskId={}, errorMessage={}", taskId, err);
                throw new IOException(
                        "FormatConvert task failed: " + (err != null ? err.toString() : "unknown"));
            }
            Thread.sleep(pollInterval);
        }
        log.error("FormatConvert task timed out: taskId={}", taskId);
        throw new IOException("FormatConvert task timed out: " + taskId);
    }

    /** Download a conversion result to a temporary file with the requested suffix. */
    public File downloadResultFile(String outputUrl, String filenamePrefix, String filenameSuffix)
            throws IOException {
        byte[] bytes = restTemplate.getForObject(outputUrl, byte[].class);
        if (bytes == null || bytes.length == 0) {
            throw new IOException("Empty result from FormatConvert: " + outputUrl);
        }
        Path tmp = Files.createTempFile(filenamePrefix, filenameSuffix);
        Files.write(tmp, bytes);
        return tmp.toFile();
    }

    /** Download the final PDF result to a temporary file. */
    public File downloadResultPdf(String outputUrl) throws IOException {
        return downloadResultFile(outputUrl, "formatconvert_result_", ".pdf");
    }

    /**
     * Remote office/image → PDF (single task). Result file lives under a dedicated temp directory
     * so callers can safely delete {@code file.getParentFile()}.
     *
     * @throws IOException if extension is not supported by remote API (caller may fall back to
     *     LibreOffice)
     */
    public File convertToPdf(MultipartFile inputFile) throws IOException, InterruptedException {
        if (!isEnabled()) {
            throw new IllegalStateException(
                    "FormatConvert service is not enabled or baseUrl is not configured");
        }
        String name = inputFile.getOriginalFilename();
        if (name == null || name.isBlank()) {
            name = "input.bin";
        }
        String ext = FilenameUtils.getExtension(name).toLowerCase(Locale.ROOT);
        String sourceFormat = mapExtensionToConvertSourceFormat(ext);
        if (sourceFormat == null) {
            throw new IOException(
                    "FormatConvert remote conversion not supported for extension: " + ext);
        }
        String base = FilenameUtils.getBaseName(name);
        if (base == null || base.isBlank()) {
            base = "input";
        }
        Path workDir = Files.createTempDirectory("formatconvert_office2pdf_");
        Path inputPath = workDir.resolve(base + "." + ext);
        inputFile.transferTo(inputPath.toFile());
        try {
            String taskId = submitTask(inputPath.toFile(), base + "." + ext, sourceFormat, "pdf");
            String pdfUrl = waitForOutputUrl(taskId);
            byte[] pdfBytes = restTemplate.getForObject(pdfUrl, byte[].class);
            if (pdfBytes == null || pdfBytes.length == 0) {
                throw new IOException("Empty PDF from FormatConvert: " + pdfUrl);
            }
            Path outPath = workDir.resolve(base + "_converted.pdf");
            Files.write(outPath, pdfBytes);
            return outPath.toFile();
        } finally {
            try {
                Files.deleteIfExists(inputPath);
            } catch (IOException e) {
                log.debug("Failed to delete FormatConvert temp input: {}", inputPath, e);
            }
        }
    }

    /**
     * Remote PDF → Office conversion for formats supported by the FormatConvert service.
     *
     * @throws IOException if the requested target format is not supported remotely
     */
    public File convertPdfToOffice(MultipartFile inputFile, String targetFormat)
            throws IOException, InterruptedException {
        if (!isEnabled()) {
            throw new IllegalStateException(
                    "FormatConvert service is not enabled or baseUrl is not configured");
        }

        String normalizedTargetFormat =
                targetFormat == null ? "" : targetFormat.toLowerCase(Locale.ROOT);
        if (!Set.of("docx", "xlsx", "pptx").contains(normalizedTargetFormat)) {
            throw new IOException(
                    "FormatConvert remote PDF conversion not supported for target format: "
                            + targetFormat);
        }

        String originalFilename = inputFile.getOriginalFilename();
        String safeFilename =
                (originalFilename == null || originalFilename.isBlank())
                        ? "input.pdf"
                        : originalFilename;

        Path workDir = Files.createTempDirectory("formatconvert_pdf2office_");
        Path inputPath = workDir.resolve("input.pdf");
        inputFile.transferTo(inputPath.toFile());

        try {
            String taskId =
                    submitTask(inputPath.toFile(), safeFilename, "pdf", normalizedTargetFormat);
            String outputUrl = waitForOutputUrl(taskId);
            Path outputPath = workDir.resolve("converted." + normalizedTargetFormat);
            byte[] outputBytes = restTemplate.getForObject(outputUrl, byte[].class);
            if (outputBytes == null || outputBytes.length == 0) {
                throw new IOException("Empty result from FormatConvert: " + outputUrl);
            }
            Files.write(outputPath, outputBytes);
            return outputPath.toFile();
        } finally {
            try {
                Files.deleteIfExists(inputPath);
            } catch (IOException e) {
                log.debug("Failed to delete FormatConvert temp PDF input: {}", inputPath, e);
            }
        }
    }

    /** Extensions supported by {@link #convertToPdf(MultipartFile)} on the remote service. */
    private static String mapExtensionToConvertSourceFormat(String ext) {
        if (ext == null || ext.isEmpty()) {
            return null;
        }
        if (Set.of("docx", "doc").contains(ext)) {
            return "docx";
        }
        if (Set.of("xlsx", "xls").contains(ext)) {
            return "xlsx";
        }
        if (Set.of("pptx", "ppt").contains(ext)) {
            return "pptx";
        }
        if (Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp", "tif", "tiff").contains(ext)) {
            return "image";
        }
        return null;
    }

    /**
     * Full OCR pipeline: PDF → Word (DOCX) → PDF via remote FormatConvert. Intermediate DOCX is
     * downloaded and re-uploaded so the final PDF carries searchable text from the Word round-trip.
     */
    public File ocrPdfViaFormatConvert(File inputPdf, String originalFilename)
            throws IOException, InterruptedException {
        // 1) PDF -> DOCX
        String pdfToDocxTaskId = submitTask(inputPdf, originalFilename, "pdf", "docx");
        String docxUrl = waitForOutputUrl(pdfToDocxTaskId);
        File docxFile = null;
        try {
            // 下载 DOCX
            byte[] docxBytes = restTemplate.getForObject(docxUrl, byte[].class);
            if (docxBytes == null || docxBytes.length == 0) {
                throw new IOException("Empty DOCX from FormatConvert: " + docxUrl);
            }
            Path docxTmp = Files.createTempFile("formatconvert_docx_", ".docx");
            Files.write(docxTmp, docxBytes);
            docxFile = docxTmp.toFile();

            // 2) DOCX -> PDF（带 OCR 文本层）
            String docxToPdfTaskId = submitTask(docxFile, docxFile.getName(), "docx", "pdf");
            String pdfUrl = waitForOutputUrl(docxToPdfTaskId);
            return downloadResultPdf(pdfUrl);
        } finally {
            if (docxFile != null && docxFile.exists() && !docxFile.delete()) {
                log.debug("Failed to delete temporary DOCX from FormatConvert: {}", docxFile);
            }
        }
    }
}
