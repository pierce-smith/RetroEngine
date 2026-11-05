package com.retro.engine.editor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

/**
 * Embeds a .ico into a 64-bit Windows executable in pure Java, so a Windows launcher can get its icon on any host
 * (jpackage does this through a Windows-only native library).
 * <p>
 * The resource tree is rebuilt with the icon added and written to a new section appended to the file, then the
 * resource data directory is pointed at it. Nothing already in the image moves, so no other offset needs fixing; the
 * old .rsrc section just becomes unreferenced.
 */
final class WindowsExeIcon {

    private static final int RT_ICON = 3;
    private static final int RT_GROUP_ICON = 14;
    private static final int LANG_EN_US = 0x409;
    private static final int GROUP_ICON_ID = 1;
    private static final int HIGH_BIT = 0x80000000;
    private static final int PE32_PLUS_MAGIC = 0x20B;
    private static final int RESOURCE_DIR = 2;
    private static final int SECURITY_DIR = 4;
    private static final int SECTION_HEADER_SIZE = 40;
    /** IMAGE_SCN_CNT_INITIALIZED_DATA | IMAGE_SCN_MEM_READ. */
    private static final int RSRC_CHARACTERISTICS = 0x40000040;

    /** type id -> name id -> language id -> resource bytes; sorted, as the PE format requires. */
    private static final class Resources extends TreeMap<Integer, TreeMap<Integer, TreeMap<Integer, byte[]>>> {
        void put(int type, int name, int lang, byte[] data) {
            computeIfAbsent(type, k -> new TreeMap<>()).computeIfAbsent(name, k -> new TreeMap<>()).put(lang, data);
        }
    }

    private WindowsExeIcon() {}

    /** Returns a copy of {@code exe} using {@code ico} as its application icon. */
    static byte[] embed(byte[] exe, byte[] ico) throws IOException {
        try {
            return embedChecked(exe, ico);
        } catch (RuntimeException e) { // Truncated or corrupt input surfaces as buffer/index errors
            throw new IOException("Malformed executable or icon: " + e, e);
        }
    }

    private static byte[] embedChecked(byte[] exe, byte[] ico) throws IOException {
        ByteBuffer pe = ByteBuffer.wrap(exe).order(ByteOrder.LITTLE_ENDIAN);
        int coff = pe.getInt(0x3C) + 4;
        int optional = coff + 20;
        if (pe.getInt(coff - 4) != 0x00004550 || (pe.getShort(optional) & 0xFFFF) != PE32_PLUS_MAGIC) {
            throw new IOException("Not a 64-bit PE executable");
        }
        int sectionCount = pe.getShort(coff + 2) & 0xFFFF;
        int sections = optional + (pe.getShort(coff + 16) & 0xFFFF);
        int sectionAlign = pe.getInt(optional + 32);
        int fileAlign = pe.getInt(optional + 36);
        int dataDirs = optional + 112;
        if (pe.getInt(dataDirs + SECURITY_DIR * 8) != 0) throw new IOException("Executable is signed; embedding would break the signature");

        int imageEnd = 0;
        int fileEnd = 0;
        int firstRaw = Integer.MAX_VALUE;
        for (int i = 0; i < sectionCount; i++) {
            int s = sections + i * SECTION_HEADER_SIZE;
            imageEnd = Math.max(imageEnd, pe.getInt(s + 12) + pe.getInt(s + 8));
            int raw = pe.getInt(s + 20);
            if (raw != 0) firstRaw = Math.min(firstRaw, raw);
            fileEnd = Math.max(fileEnd, raw + pe.getInt(s + 16));
        }
        if (fileEnd != exe.length) throw new IOException("Executable has trailing data");
        int newHeader = sections + sectionCount * SECTION_HEADER_SIZE;
        if (newHeader + SECTION_HEADER_SIZE > firstRaw) throw new IOException("No room for another section header");

        Resources resources = readResources(pe, sections, sectionCount, pe.getInt(dataDirs + RESOURCE_DIR * 8));
        resources.remove(RT_ICON);
        resources.remove(RT_GROUP_ICON);
        addIcon(resources, ico);

        int sectionRva = align(imageEnd, sectionAlign);
        int sectionRaw = align(exe.length, fileAlign);
        byte[] rsrc = writeResources(resources, sectionRva);
        int rawSize = align(rsrc.length, fileAlign);

        byte[] out = Arrays.copyOf(exe, sectionRaw + rawSize);
        System.arraycopy(rsrc, 0, out, sectionRaw, rsrc.length);
        ByteBuffer o = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        o.position(newHeader);
        o.put(new byte[] { '.', 'r', 's', 'r', 'c', '2', 0, 0 });
        o.putInt(rsrc.length).putInt(sectionRva).putInt(rawSize).putInt(sectionRaw);
        o.putInt(0).putInt(0).putShort((short) 0).putShort((short) 0); // No relocations or line numbers
        o.putInt(RSRC_CHARACTERISTICS);
        o.putShort(coff + 2, (short) (sectionCount + 1));
        o.putInt(optional + 8, o.getInt(optional + 8) + rawSize); // SizeOfInitializedData
        o.putInt(optional + 56, align(sectionRva + rsrc.length, sectionAlign)); // SizeOfImage
        o.putInt(optional + 64, 0); // CheckSum: not validated for applications, and now stale
        o.putInt(dataDirs + RESOURCE_DIR * 8, sectionRva);
        o.putInt(dataDirs + RESOURCE_DIR * 8 + 4, rsrc.length);
        return out;
    }

    private static int align(int value, int alignment) {
        return (value + alignment - 1) / alignment * alignment;
    }

    /** Maps an RVA to a file offset using the section table. */
    private static int fileOffset(ByteBuffer pe, int sections, int sectionCount, int rva) throws IOException {
        for (int i = 0; i < sectionCount; i++) {
            int s = sections + i * SECTION_HEADER_SIZE;
            int va = pe.getInt(s + 12);
            if (rva >= va && rva < va + Math.max(pe.getInt(s + 8), pe.getInt(s + 16))) return rva - va + pe.getInt(s + 20);
        }
        throw new IOException("RVA outside every section: " + Integer.toHexString(rva));
    }

    private static Resources readResources(ByteBuffer pe, int sections, int sectionCount, int rootRva) throws IOException {
        Resources resources = new Resources();
        if (rootRva == 0) return resources;
        int root = fileOffset(pe, sections, sectionCount, rootRva);
        for (Map.Entry<Integer, Integer> type : directory(pe, root, 0).entrySet()) {
            for (Map.Entry<Integer, Integer> name : directory(pe, root, type.getValue()).entrySet()) {
                for (Map.Entry<Integer, Integer> lang : directory(pe, root, name.getValue()).entrySet()) {
                    int entry = root + lang.getValue();
                    int data = fileOffset(pe, sections, sectionCount, pe.getInt(entry));
                    byte[] bytes = new byte[pe.getInt(entry + 4)];
                    pe.get(data, bytes);
                    resources.put(type.getKey(), name.getKey(), lang.getKey(), bytes);
                }
            }
        }
        return resources;
    }

    /** Entries of the directory at {@code offset} from the resource root: id -> offset of the child. */
    private static Map<Integer, Integer> directory(ByteBuffer pe, int root, int offset) throws IOException {
        int dir = root + offset;
        int named = pe.getShort(dir + 12) & 0xFFFF;
        int ids = pe.getShort(dir + 14) & 0xFFFF;
        if (named != 0) throw new IOException("Named resources are not supported");
        Map<Integer, Integer> entries = new TreeMap<>();
        for (int i = 0; i < ids; i++) {
            int entry = dir + 16 + i * 8;
            entries.put(pe.getInt(entry), pe.getInt(entry + 4) & ~HIGH_BIT);
        }
        return entries;
    }

    /** Splits a .ico into one RT_ICON per image plus the RT_GROUP_ICON that indexes them. */
    private static void addIcon(Resources resources, byte[] ico) throws IOException {
        ByteBuffer in = ByteBuffer.wrap(ico).order(ByteOrder.LITTLE_ENDIAN);
        int count = in.getShort(4) & 0xFFFF;
        if (in.getShort(0) != 0 || in.getShort(2) != 1 || count == 0) throw new IOException("Not a valid .ico file");
        ByteBuffer group = ByteBuffer.allocate(6 + count * 14).order(ByteOrder.LITTLE_ENDIAN);
        group.putShort((short) 0).putShort((short) 1).putShort((short) count);
        for (int i = 0; i < count; i++) {
            int entry = 6 + i * 16;
            byte[] image = new byte[in.getInt(entry + 8)];
            in.get(in.getInt(entry + 12), image);
            int id = i + 1;
            resources.put(RT_ICON, id, LANG_EN_US, image);
            group.put(ico, entry, 12).putShort((short) id); // Same fields as ICONDIRENTRY, with an id for the offset
        }
        resources.put(RT_GROUP_ICON, GROUP_ICON_ID, LANG_EN_US, group.array());
    }

    private static int directorySize(int entries) {
        return 16 + entries * 8;
    }

    private static void directoryHeader(ByteBuffer out, int offset, int entries) {
        out.putShort(offset + 14, (short) entries); // Characteristics, timestamp, version and named count stay 0
    }

    /** Serializes the tree as directories, then data entries, then the data, with RVAs based at {@code sectionRva}. */
    private static byte[] writeResources(Resources resources, int sectionRva) {
        int directories = directorySize(resources.size());
        int leaves = 0;
        int data = 0;
        for (var names : resources.values()) {
            directories += directorySize(names.size());
            for (var langs : names.values()) {
                directories += directorySize(langs.size());
                leaves += langs.size();
                for (byte[] bytes : langs.values()) data += align(bytes.length, 8);
            }
        }
        ByteBuffer out = ByteBuffer.allocate(directories + leaves * 16 + data).order(ByteOrder.LITTLE_ENDIAN);
        int nextDir = directorySize(resources.size());
        int nextEntry = directories;
        int nextData = directories + leaves * 16;

        directoryHeader(out, 0, resources.size());
        int typeSlot = 16;
        for (var type : resources.entrySet()) {
            int typeDir = nextDir;
            nextDir += directorySize(type.getValue().size());
            out.putInt(typeSlot, type.getKey()).putInt(typeSlot + 4, typeDir | HIGH_BIT);
            typeSlot += 8;
            directoryHeader(out, typeDir, type.getValue().size());
            int nameSlot = typeDir + 16;
            for (var name : type.getValue().entrySet()) {
                int nameDir = nextDir;
                nextDir += directorySize(name.getValue().size());
                out.putInt(nameSlot, name.getKey()).putInt(nameSlot + 4, nameDir | HIGH_BIT);
                nameSlot += 8;
                directoryHeader(out, nameDir, name.getValue().size());
                int langSlot = nameDir + 16;
                for (var lang : name.getValue().entrySet()) {
                    byte[] bytes = lang.getValue();
                    out.putInt(langSlot, lang.getKey()).putInt(langSlot + 4, nextEntry);
                    langSlot += 8;
                    out.putInt(nextEntry, sectionRva + nextData).putInt(nextEntry + 4, bytes.length);
                    nextEntry += 16; // Code page and reserved stay 0
                    out.put(nextData, bytes);
                    nextData += align(bytes.length, 8);
                }
            }
        }
        return out.array();
    }
}
