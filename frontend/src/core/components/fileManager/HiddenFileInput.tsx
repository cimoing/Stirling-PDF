import React from 'react';
import { useFileManagerContext } from '@app/contexts/FileManagerContext';

const HiddenFileInput: React.FC = () => {
  const { fileInputRef, onFileInputChange, fileInputAccept } =
    useFileManagerContext();

  return (
    <input
      ref={fileInputRef}
      type="file"
      multiple={true}
      accept={fileInputAccept}
      onChange={onFileInputChange}
      style={{ display: 'none' }}
      data-testid="file-input"
    />
  );
};

export default HiddenFileInput;
