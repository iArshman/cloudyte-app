import re

# PdfViewerScreen.kt
with open('app/src/main/java/com/example/ui/screens/PdfViewerScreen.kt', 'r') as f:
    text = f.read()

text = re.sub(r'val offlineFile: java\.io\.File\? = null\n\s*val fileToRender = remember\(offlineFile, pdfUrl\) \{\n\s*if \(offlineFile \!= null && offlineFile\.exists\(\) && offlineFile\.length\(\) > 0\) \{\n\s*offlineFile\n\s*\} else \{\n\s*null\n\s*\}\n\s*\}', 'val fileToRender: java.io.File? = null', text, flags=re.DOTALL)
text = re.sub(r'val offlineFile: java\.io\.File\? = null', '', text)

with open('app/src/main/java/com/example/ui/screens/PdfViewerScreen.kt', 'w') as f:
    f.write(text)

# TextEditorScreen.kt
with open('app/src/main/java/com/example/ui/screens/TextEditorScreen.kt', 'r') as f:
    text = f.read()

# We need to just fix TextEditor Screen's loading.
# Originally: val fileContent = remember(offlineFile, fileUrl) {
#    if (offlineFile != null) offlineFile.readText() else ApiClient.downloadText(fileUrl)
# }
text = re.sub(r'val offlineFile: java\.io\.File\? = null\s*val fileContent = remember\(offlineFile, fileUrl\).*?if \(false\) \{.*?\} else \{', 'val fileContent = remember(fileUrl) {', text, flags=re.DOTALL)

with open('app/src/main/java/com/example/ui/screens/TextEditorScreen.kt', 'w') as f:
    f.write(text)

