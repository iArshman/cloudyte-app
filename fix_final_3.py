import re

with open('app/src/main/java/com/example/ui/screens/ImageViewerScreen.kt', 'r') as f:
    text = f.read()
text = text.replace('coil.compose.SubcomposeAsyncImageContent', 'coil.compose.SubcomposeAsyncImageContent()')
with open('app/src/main/java/com/example/ui/screens/ImageViewerScreen.kt', 'w') as f:
    f.write(text)

with open('app/src/main/java/com/example/ui/screens/FilesScreen.kt', 'r') as f:
    text = f.read()
# Replace `downloadedFiles` in remember block
text = re.sub(r'downloadedFiles(\s*\} \{)', r'files\1', text)
text = text.replace(', downloadedFiles', '')
# Set Grid View as default
text = text.replace('var isGridView by remember { mutableStateOf(false) }', 'var isGridView by remember { mutableStateOf(true) }')

with open('app/src/main/java/com/example/ui/screens/FilesScreen.kt', 'w') as f:
    f.write(text)
