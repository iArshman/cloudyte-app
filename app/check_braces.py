with open("app/src/main/java/com/example/ui/screens/FilesScreen.kt") as f:
    lines = f.readlines()

depth = 0
for idx, line in enumerate(lines):
    line_num = idx + 1
    # Simple count
    open_c = line.count("{")
    close_c = line.count("}")
    depth += open_c - close_c
    if line_num in [368, 369, 1618, 1619, 1620, 1623, 1897, 2160, 2161, 2162, 2163, 2164]:
        print(f"Line {line_num}: depth={depth}, opens={open_c}, closes={close_c}, line: {line.strip()}")
print(f"Total depth at end: {depth}")
