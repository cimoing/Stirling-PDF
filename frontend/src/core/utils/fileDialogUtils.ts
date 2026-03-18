const DEFAULT_DOCUMENT_DIALOG_EXTENSIONS = [
  'pdf',
  'jpg',
  'jpeg',
  'png',
  'gif',
  'tiff',
  'bmp',
  'html',
  'zip',
] as const;

export function getDocumentFileDialogFilter() {
  return [
    {
      name: 'Documents',
      extensions: [...DEFAULT_DOCUMENT_DIALOG_EXTENSIONS],
    },
  ];
}

/**
 * Native file dialog filters for tools that declare {@link supportedFormats} (e.g. Convert).
 * Uses the full extension list when it includes Office or other non-default types.
 */
export function getFileDialogFiltersForSupportedFormats(
  supportedFormats: string[] | undefined | null
): Array<{ name: string; extensions: string[] }> {
  if (!supportedFormats?.length) {
    return getDocumentFileDialogFilter();
  }
  const normalized = supportedFormats
    .map((e) => e.replace(/^\./, '').toLowerCase())
    .filter(Boolean);
  const onlyDefaults =
    normalized.length > 0 &&
    normalized.every((e) =>
      (DEFAULT_DOCUMENT_DIALOG_EXTENSIONS as readonly string[]).includes(e)
    );
  if (onlyDefaults) {
    return getDocumentFileDialogFilter();
  }
  const uniq = [...new Set(normalized)];
  return [{ name: 'Supported formats', extensions: uniq }];
}

/** HTML file input `accept` string for the same extension set. */
export function supportedFormatsToAcceptAttribute(
  supportedFormats: string[] | undefined | null
): string | undefined {
  if (!supportedFormats?.length) {
    return undefined;
  }
  const normalized = [
    ...new Set(
      supportedFormats
        .map((e) => e.replace(/^\./, '').toLowerCase())
        .filter(Boolean)
    ),
  ];
  if (normalized.length === 0) {
    return undefined;
  }
  return normalized.map((e) => `.${e}`).join(',');
}
