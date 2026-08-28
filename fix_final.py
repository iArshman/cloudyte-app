import re

with open('app/src/main/java/com/example/ui/screens/VideoPlayerScreen.kt', 'r') as f:
    text = f.read()

text = text.replace('MediaItem.fromUri(audioUrl)', 'MediaItem.fromUri(initialUrl)')
with open('app/src/main/java/com/example/ui/screens/VideoPlayerScreen.kt', 'w') as f:
    f.write(text)

