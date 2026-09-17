1. Investigate the bug in file selection and count mismatch in datasets.
2. The mismatch is mostly caused by the `getFileMetadatasSorted`, `getFileMetadatasSortedByLabelAndFolder`, `getFileMetadatasFolderListing`, `getFileMetadataCount` functions not filtering out deleted files (when a file in a draft version is deleted, it might just set the `deleted` flag to `true` on the `DataFile` but keep it in the list).
3. The dataset version service returns inconsistent items for deleted files.
4. Provide a fix to exclude files that have `getDeleted()` evaluating to `true` when fetching file lists and file counts.
5. Provide a test case proving the behavior using Mockito or regular unit testing in `DatasetVersionFileMetadataTest`.
6. Complete pre-commit tests and verify functionality.
7. Submit the PR.
