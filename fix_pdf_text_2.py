import re

with open('app/src/main/java/com/example/ui/screens/TextEditorScreen.kt', 'r') as f:
    text = f.read()

# Fix TextEditorScreen
text = re.sub(r'val fileContent = remember\(fileUrl\) \{\s*if \(false\) \{\s*\} else \{\s*ApiClient\.downloadText\(fileUrl\)\s*\}\s*\}', 'val fileContent = remember(fileUrl) { ApiClient.downloadText(fileUrl) }', text, flags=re.DOTALL)
text = text.replace('val offlineFile: java.io.File? = null', '')
text = re.sub(r'if \(false\) \{.*?\} else \{', '', text, flags=re.DOTALL)

with open('app/src/main/java/com/example/ui/screens/TextEditorScreen.kt', 'w') as f:
    f.write(text)

with open('app/src/main/java/com/example/ui/screens/PdfViewerScreen.kt', 'r') as f:
    text = f.read()

text = re.sub(r'val fileToRender.*?= null', '', text)
text = re.sub(r'if \(false.*?else \{', '', text, flags=re.DOTALL)
text = text.replace('fileToRender ?: ', '')
text = text.replace('val offlineFile: java.io.File? = null', '')

with open('app/src/main/java/com/example/ui/screens/PdfViewerScreen.kt', 'w') as f:
    f.write(text)
