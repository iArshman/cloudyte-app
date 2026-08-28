with open('app/src/main/java/com/example/ui/AppNavigation.kt', 'r') as f:
    text = f.read()

text = text.replace('FileDetailScreen(navController, accountId, fileId)', 'FileDetailScreen(navController, accountId, fileId, FileRepository.getActiveList())')
text = text.replace('VideoPlayerScreen(navController, accountId, fileId)', 'VideoPlayerScreen(navController, accountId, fileId, FileRepository.getActiveList())')
text = text.replace('ImageViewerScreen(navController, accountId, fileId)', 'ImageViewerScreen(navController, accountId, fileId, FileRepository.getActiveList())')
text = text.replace('AudioPlayerScreen(navController, accountId, fileId)', 'AudioPlayerScreen(navController, accountId, fileId, FileRepository.getActiveList())')
text = text.replace('TextEditorScreen(navController, accountId, fileId)', 'TextEditorScreen(navController, accountId, fileId, FileRepository.getActiveList())')
text = text.replace('PdfViewerScreen(navController, accountId, fileId)', 'PdfViewerScreen(navController, accountId, fileId, FileRepository.getActiveList())')

with open('app/src/main/java/com/example/ui/AppNavigation.kt', 'w') as f:
    f.write(text)
