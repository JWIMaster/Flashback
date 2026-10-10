package com.moulberry.flashback;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Validates every member a mixin refers to against the real mapped class files.
 *
 * <p>Mixin resolves these lazily, when the class is first loaded, so a bad target is not a compile
 * error: it is a crash at game start. That is how {@code Mth;lengthSquared:(FF)F} - the colon form,
 * which is only valid for fields - reached a player, and why the transform failed on
 * {@code LevelRenderer.<init>}. This check parses the mixin sources and verifies each reference up
 * front, so the same mistake fails the suite instead.</p>
 *
 * <p>The class files are read straight out of the jars on the classpath with ASM rather than
 * reflected, because loading a game class resolves everything it mentions and several of them
 * refer to libraries that are not present here.</p>
 */
public class MixinTargetCheck implements Opcodes {

    private static final Pattern MIXIN = Pattern.compile("@Mixin\\s*\\(([^)]*)\\)");
    private static final Pattern CLASS_LITERAL = Pattern.compile("([\\w.$]+)\\.class");
    private static final Pattern TARGETS_ATTR = Pattern.compile("\\btargets\\s*=\\s*(\\{[^}]*\\}|\"[^\"]*\")");
    private static final Pattern METHOD_ATTR = Pattern.compile("\\bmethod\\s*=\\s*(\\{[^}]*\\}|\"[^\"]*\")");
    private static final Pattern TARGET_ATTR = Pattern.compile("\\btarget\\s*=\\s*(\\{[^}]*\\}|\"[^\"]*\")");
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]*)\"");
    private static final Pattern IMPORT = Pattern.compile("^import\\s+([\\w.$]+);");
    private static final Pattern PACKAGE = Pattern.compile("^package\\s+([\\w.]+);");
    private static final Pattern SHADOW_FIELD = Pattern.compile("@Shadow[\\w\\s.()=,]*?\\s[\\w.$<>\\[\\], ]+?\\s+(\\w+)\\s*(?:=|;)");
    private static final Pattern SHADOW_METHOD = Pattern.compile("@Shadow[\\w\\s.()=,]*?\\s[\\w.$<>\\[\\], ]+?\\s+(\\w+)\\s*\\(");

    private static int targetsChecked;
    private static int methodsChecked;
    private static int shadowsChecked;
    private static int skipped;

    public static void main(String[] args) throws IOException {
        Path root = Path.of(args.length > 0 ? args[0] : "src/main/java");
        ClassIndex index = new ClassIndex();

        List<Path> sources = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(".java")) {
                    sources.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });

        List<String> problems = new ArrayList<>();
        for (Path source : sources) {
            // Comments hold disabled annotations and prose about targets; only real code counts.
            String text = stripComments(Files.readString(source));
            if (text.contains("@Mixin")) {
                checkFile(index, source, text, problems);
            }
        }

        if (!problems.isEmpty()) {
            for (String problem : problems) {
                System.out.println("FAIL " + problem);
            }
            throw new AssertionError(problems.size() + " invalid mixin member reference(s)");
        }
        System.out.println("All mixin targets resolve (" + targetsChecked + " targets, " + methodsChecked
            + " methods, " + shadowsChecked + " shadows, " + skipped + " unchecked)");
    }

    private static void checkFile(ClassIndex index, Path source, String text, List<String> problems) {
        List<String> imports = new ArrayList<>();
        List<String> wildcards = new ArrayList<>();
        String packageName = "";
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            Matcher pkg = PACKAGE.matcher(trimmed);
            if (pkg.find()) {
                packageName = pkg.group(1);
                continue;
            }
            Matcher imp = IMPORT.matcher(trimmed);
            if (imp.find()) {
                String name = imp.group(1);
                if (name.endsWith(".*")) {
                    wildcards.add(name.substring(0, name.length() - 2));
                } else {
                    imports.add(name);
                }
            }
        }

        Set<String> targets = new LinkedHashSet<>();
        Matcher mixin = MIXIN.matcher(text);
        while (mixin.find()) {
            for (String literal : classLiterals(mixin.group(1))) {
                String resolved = index.resolve(literal, imports, wildcards, packageName);
                if (resolved == null) {
                    skipped++;
                } else {
                    targets.add(resolved);
                }
            }
        }
        Matcher targetsAttr = TARGETS_ATTR.matcher(text);
        while (targetsAttr.find()) {
            for (String value : quoted(targetsAttr.group(1))) {
                if (index.meta(value.replace('.', '/')) != null) {
                    targets.add(value);
                } else {
                    skipped++;
                }
            }
        }

        for (String target : targets) {
            checkMembers(index, source, text, target, problems);
        }
    }

    private static List<String> classLiterals(String annotationBody) {
        List<String> names = new ArrayList<>();
        Matcher matcher = CLASS_LITERAL.matcher(annotationBody);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private static void checkMembers(ClassIndex index, Path source, String text, String target, List<String> problems) {
        String where = source.getFileName() + " -> " + target.substring(target.lastIndexOf('/') + 1);
        // Member existence can only be enforced for the game itself, where the classpath version is
        // the one that will run. A mod compat mixin is @Pseudo with require = 0 precisely because
        // the installed version may differ, so there only the syntax is checked.
        boolean strict = isGameClass(target);

        // target = "Lowner;name(args)ret" for methods, "Lowner;name:desc" for fields.
        Matcher targets = TARGET_ATTR.matcher(text);
        while (targets.find()) {
            for (String value : quoted(targets.group(1))) {
                if (value.contains("*") || !value.startsWith("L")) {
                    continue;
                }
                int semicolon = value.indexOf(';');
                if (semicolon < 0) {
                    continue;
                }
                String owner = value.substring(1, semicolon);
                String rest = value.substring(semicolon + 1);
                if (index.meta(owner) == null) {
                    skipped++;
                    continue;
                }
                targetsChecked++;

                int paren = rest.indexOf('(');
                if (paren >= 0) {
                    String name = rest.substring(0, paren);
                    if (name.endsWith(":")) {
                        problems.add(where + ": method target \"" + value
                            + "\" uses the field colon form; write owner;name(args)ret instead");
                        continue;
                    }
                    if (isGameClass(owner) && !index.hasMethod(owner, rest)) {
                        problems.add(where + ": " + owner + " has no method " + rest);
                    } else if (!isGameClass(owner)) {
                        skipped++;
                    }
                } else {
                    String name = rest.split(":")[0];
                    if (isGameClass(owner) && !index.hasField(owner, name)) {
                        problems.add(where + ": " + owner + " has no field " + name);
                    }
                }
            }
        }

        Matcher methods = METHOD_ATTR.matcher(text);
        while (methods.find()) {
            for (String value : quoted(methods.group(1))) {
                if (value.contains("*") || value.startsWith("@")) {
                    // Wildcards, and MixinSquared's "@Mod:Handler" markers, are not member names.
                    continue;
                }
                methodsChecked++;
                int paren = value.indexOf('(');
                String name = (paren >= 0 ? value.substring(0, paren) : value).split(":")[0];
                if (name.endsWith(":")) {
                    problems.add(where + ": method \"" + value + "\" uses the field colon form; write name(args)ret instead");
                    continue;
                }
                boolean found = paren >= 0 ? index.hasMethod(target, value) : index.hasMethodNamed(target, name);
                if (!found) {
                    if (strict) {
                        problems.add(where + ": " + target.substring(target.lastIndexOf('/') + 1) + " has no method " + value);
                    } else {
                        skipped++;
                    }
                }
            }
        }

        Matcher shadows = SHADOW_FIELD.matcher(text);
        while (shadows.find()) {
            shadowsChecked++;
            String name = shadows.group(1);
            if (!index.hasField(target, name) && !index.hasMethodNamed(target, name)) {
                if (strict) {
                    problems.add(where + ": nothing to shadow called " + name);
                } else {
                    skipped++;
                }
            }
        }
        Matcher shadowMethods = SHADOW_METHOD.matcher(text);
        while (shadowMethods.find()) {
            shadowsChecked++;
            String name = shadowMethods.group(1);
            if ("class".equals(name) || "interface".equals(name) || "enum".equals(name) || "record".equals(name)) {
                continue;
            }
            if (!index.hasMethodNamed(target, name)) {
                if (strict) {
                    problems.add(where + ": nothing to shadow called " + name + "()");
                } else {
                    skipped++;
                }
            }
        }
    }

    private static boolean isGameClass(String internalName) {
        return internalName.startsWith("net/minecraft/") || internalName.startsWith("com/mojang/");
    }

    /** Removes line and block comments, leaving string and char literals intact. */
    private static String stripComments(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
                while (i < text.length() && text.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < text.length() && !(text.charAt(i) == '*' && text.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(text.length(), i + 2);
            } else if (c == '"' || c == '\'') {
                int end = i + 1;
                while (end < text.length() && text.charAt(end) != c) {
                    end += text.charAt(end) == '\\' ? 2 : 1;
                }
                end = Math.min(end + 1, text.length());
                out.append(text, i, end);
                i = end;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static List<String> quoted(String group) {
        List<String> values = new ArrayList<>();
        Matcher matcher = QUOTED.matcher(group);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }

    /** Declared members and hierarchy of one class, read from its bytes. */
    private static final class Meta {
        String superName;
        String[] interfaces = new String[0];
        final Set<String> methods = new HashSet<>();
        final Set<String> methodNames = new HashSet<>();
        final Set<String> fieldNames = new HashSet<>();
    }

    /** Finds class files in the jars on this process's classpath. */
    private static final class ClassIndex {
        private final List<ZipFile> jars = new ArrayList<>();
        private final Map<String, Meta> cache = new HashMap<>();

        ClassIndex() {
            for (String entry : System.getProperty("java.class.path", "").split(java.io.File.pathSeparator)) {
                if (!entry.endsWith(".jar")) {
                    continue;
                }
                try {
                    jars.add(new ZipFile(entry));
                } catch (IOException ignored) {
                    // Not readable as a jar; nothing to index from it.
                }
            }
        }

        Meta meta(String internalName) {
            if (internalName == null) {
                return null;
            }
            if (cache.containsKey(internalName)) {
                return cache.get(internalName);
            }
            Meta meta = read(internalName);
            cache.put(internalName, meta);
            return meta;
        }

        private Meta read(String internalName) {
            for (ZipFile jar : jars) {
                ZipEntry entry = jar.getEntry(internalName + ".class");
                if (entry == null) {
                    continue;
                }
                try (InputStream stream = jar.getInputStream(entry)) {
                    Meta meta = new Meta();
                    new ClassReader(stream.readAllBytes()).accept(new ClassVisitor(ASM9) {
                        @Override
                        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                            meta.superName = superName;
                            meta.interfaces = interfaces == null ? new String[0] : interfaces;
                        }

                        @Override
                        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                            meta.methods.add(name + descriptor);
                            meta.methodNames.add(name);
                            return null;
                        }

                        @Override
                        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                            meta.fieldNames.add(name);
                            return null;
                        }
                    }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    return meta;
                } catch (Throwable t) {
                    return null;
                }
            }
            return null;
        }

        boolean hasMethod(String owner, String nameAndDescriptor) {
            for (String current = owner; current != null; ) {
                Meta meta = meta(current);
                if (meta == null) {
                    return false;
                }
                if (meta.methods.contains(nameAndDescriptor)) {
                    return true;
                }
                for (String iface : meta.interfaces) {
                    if (hasMethod(iface, nameAndDescriptor)) {
                        return true;
                    }
                }
                current = meta.superName;
            }
            return false;
        }

        boolean hasMethodNamed(String owner, String name) {
            for (String current = owner; current != null; ) {
                Meta meta = meta(current);
                if (meta == null) {
                    return false;
                }
                if (meta.methodNames.contains(name)) {
                    return true;
                }
                for (String iface : meta.interfaces) {
                    if (hasMethodNamed(iface, name)) {
                        return true;
                    }
                }
                current = meta.superName;
            }
            return false;
        }

        boolean hasField(String owner, String name) {
            for (String current = owner; current != null; ) {
                Meta meta = meta(current);
                if (meta == null) {
                    return false;
                }
                if (meta.fieldNames.contains(name)) {
                    return true;
                }
                current = meta.superName;
            }
            return false;
        }

        /** Turns a name as written in source into an internal name, or null if it is absent. */
        String resolve(String name, List<String> imports, List<String> wildcards, String packageName) {
            if (name.contains(".")) {
                String internal = name.replace('.', '/');
                return meta(internal) != null ? internal : null;
            }
            for (String candidate : imports) {
                if (candidate.endsWith("." + name)) {
                    String internal = candidate.replace('.', '/');
                    if (meta(internal) != null) {
                        return internal;
                    }
                }
            }
            List<String> packages = new ArrayList<>();
            if (!packageName.isEmpty()) {
                packages.add(packageName);
            }
            packages.addAll(wildcards);
            packages.add("java.lang");
            for (String candidate : packages) {
                String internal = candidate.replace('.', '/') + "/" + name;
                if (meta(internal) != null) {
                    return internal;
                }
            }
            return null;
        }
    }

}
