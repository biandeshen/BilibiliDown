path = 'F:/WorkSpace/BilibiliDown/src/nicelee/bilibili/parsers/impl/URL4UPDynamicParser.java'
with open(path, 'r', encoding='utf-8') as f:
    lines = f.readlines()

# Find key line numbers by content
for i, line in enumerate(lines):
    # Line to add isInitialDone after
    if 'boolean getVideoLink = (boolean) obj[1];' in line:
        lines.insert(i+1, '\t\tboolean isInitialDone = DynamicsDB.isInitialScanDone(spaceID);\n')
        print(f'Added isInitialDone after line {i+1}')

    # Add video counters before the for loop
    if 'int skippedCount = 0;' in line and 'videoOnPage' not in lines[i+1] if i+1 < len(lines) else True:
        lines.insert(i+1, '\t\t\t\tint videoOnPage = 0;\n')
        lines.insert(i+2, '\t\t\t\tint videoKnown = 0;\n')
        print(f'Added counters after line {i+1}')

    # Modify recordDynamicToDB call to check contains first
    if 'recordDynamicToDB(spaceID, dynamicId, type, mods);' in line:
        indent = line[:len(line) - len(line.lstrip())]
        lines[i] = indent + 'if (!DynamicsDB.contains(spaceID, dynamicId))\n'
        lines.insert(i+1, indent + '\trecordDynamicToDB(spaceID, dynamicId, type, mods);\n')
        lines.insert(i+2, indent + 'else videoKnown++;\n')
        print(f'Modified recordDynamicToDB at line {i+1}')

    # After AV check, add videoOnPage++
    if "if (!\"DYNAMIC_TYPE_AV\".equals(type))" in line and "continue" in lines[i+1] if i+1 < len(lines) else False:
        indent = line[:len(line) - len(line.lstrip())]
        # Insert after the continue line
        for j in range(i, min(i+5, len(lines))):
            if 'continue;' in lines[j] and 'DYNAMIC_TYPE_AV' not in lines[j]:
                if 'videoOnPage' not in lines[j+1] if j+1 < len(lines) else True:
                    lines.insert(j+1, indent + 'videoOnPage++;\n')
                    print(f'Added videoOnPage++ after line {j+1}')
                break

    # Add early stop after skippedCount log
    if 'skippedCount > 0' in line and 'Logger.println' in line:
        indent = line[:len(line) - len(line.lstrip())]
        # Insert the early stop after this block's closing }
        for j in range(i, min(i+10, len(lines))):
            if lines[j].strip() == '}':
                lines.insert(j+1, indent + '// early stop: all videos on this page are known\n')
                lines.insert(j+2, indent + 'if (videoOnPage > 0 && videoKnown >= videoOnPage) {\n')
                lines.insert(j+3, indent + '\tLogger.println("all videos on page known, stop");\n')
                lines.insert(j+4, indent + '\thasMore = false;\n')
                lines.insert(j+5, indent + '}\n')
                print(f'Added early stop after line {j+1}')
                break

    # Add markInitialScanDone in the offset update section
    if 'currentOffset = null;' in line and 'hasMore' in lines[i-1] if i > 0 else False:
        indent = line[:len(line) - len(line.lstrip())]
        # Check if this is in the hasMore block
        context = ''.join(l.strip() for l in lines[max(0,i-3):i])
        if 'hasMore' in context:
            lines.insert(i+1, indent + '// full scan complete - mark as done\n')
            lines.insert(i+2, indent + 'if (!isInitialDone) {\n')
            lines.insert(i+3, indent + '\tDynamicsDB.markInitialScanDone(spaceID, pageQueryResult.getAuthor());\n')
            lines.insert(i+4, indent + '\tLogger.println("UP " + spaceID + " full scan complete");\n')
            lines.insert(i+5, indent + '}\n')
            print(f'Added markInitialScanDone after line {i+1}')
            break

with open(path, 'w', encoding='utf-8') as f:
    f.writelines(lines)
print('Done')
