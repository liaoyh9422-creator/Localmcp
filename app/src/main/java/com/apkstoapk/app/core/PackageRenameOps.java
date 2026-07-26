package com.apkstoapk.app.core;

import com.apkstoapk.app.util.SimpleApkLogger;
import com.reandroid.apk.ApkModule;
import com.reandroid.apk.DexFileInputSource;
import com.reandroid.app.AndroidManifest;
import com.reandroid.archive.ByteInputSource;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import com.reandroid.arsc.chunk.xml.ResXmlAttribute;
import com.reandroid.arsc.chunk.xml.ResXmlElement;
import com.reandroid.arsc.value.ValueType;
import com.reandroid.dex.model.DexFile;
import com.reandroid.dex.refactor.RenameTypes;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * True package rename: DEX types via ARSCLib {@link RenameTypes}, then manifest package
 * and absolute component / authority strings that still reference the old package.
 *
 * <p>Does <b>not</b> call {@link ManifestOps#setPackageName} (avoids recursion).
 * Callers should use this when applicationId and code package must move together.
 */
public final class PackageRenameOps {
    private PackageRenameOps() {}

    public static final class Result {
        public final String oldPackage;
        public final String newPackage;
        public final int dexFilesTouched;
        public final int typeMapsApplied;
        public final int manifestNamesRewritten;

        public Result(
                String oldPackage,
                String newPackage,
                int dexFilesTouched,
                int typeMapsApplied,
                int manifestNamesRewritten
        ) {
            this.oldPackage = oldPackage;
            this.newPackage = newPackage;
            this.dexFilesTouched = dexFilesTouched;
            this.typeMapsApplied = typeMapsApplied;
            this.manifestNamesRewritten = manifestNamesRewritten;
        }
    }

    public static Result rename(
            ApkModule module,
            String newPackageName,
            SimpleApkLogger logger
    ) throws Exception {
        if (module == null) throw new IllegalArgumentException("module is null");
        if (newPackageName == null || newPackageName.trim().isEmpty()) {
            throw new IllegalArgumentException("newPackageName is blank");
        }
        String next = newPackageName.trim();
        String before = module.getPackageName();
        if (before == null || before.trim().isEmpty()) {
            before = ManifestOps.requireManifest(module).getPackageName();
        }
        if (before == null || before.trim().isEmpty()) {
            throw new IllegalStateException("Cannot read current package name");
        }
        before = before.trim();
        if (before.equals(next)) {
            if (logger != null) {
                logger.bi("包名未变，跳过真改包", "Package unchanged, skip rename", next);
            }
            return new Result(before, next, 0, 0, 0);
        }

        if (logger != null) {
            logger.stage("真改包 (DEX+Manifest)", "True package rename (DEX+Manifest)");
            logger.bi("旧包名", "Old package", before);
            logger.bi("新包名", "New package", next);
        }

        int applied = renameDexPackages(module, before, next, logger);
        int manifestRewrites = rewriteAbsoluteManifestRefs(module, before, next, logger);

        // ApkModule API only (manifest package + resources table package name)
        module.setPackageName(next);
        AndroidManifestBlock manifest = module.getAndroidManifest();
        if (manifest != null) {
            manifest.refresh();
            module.setManifest(manifest);
        }

        if (logger != null) {
            logger.ok("真改包完成", "True package rename done",
                    before + " → " + next
                            + " | dexApply=" + applied
                            + " | manifestRefs=" + manifestRewrites);
        }
        return new Result(before, next, countDex(module), applied, manifestRewrites);
    }

    public static int renameDexPackages(
            ApkModule module,
            String oldPackage,
            String newPackage,
            SimpleApkLogger logger
    ) throws Exception {
        List<DexFileInputSource> dexSources = module.listDexFiles();
        if (dexSources == null || dexSources.isEmpty()) {
            if (logger != null) {
                logger.warn("无 DEX，跳过类型改名", "No DEX, skip type rename");
            }
            return 0;
        }

        int totalApplied = 0;
        for (DexFileInputSource dexSource : dexSources) {
            String dexName = dexSource.getAlias();
            if (dexName == null || dexName.isEmpty()) {
                dexName = dexSource.getName();
            }
            byte[] original;
            try (InputStream in = dexSource.openStream()) {
                original = readAll(in);
            }
            DexFile dexFile = DexFile.read(original);
            dexFile.setSimpleName(dexName);

            RenameTypes renameTypes = new RenameTypes();
            // (repo, oldPkg, newPkg, includeSubPackages)
            renameTypes.addPackage(dexFile, oldPackage, newPackage, true);
            int applied = renameTypes.apply(dexFile);
            if (applied > 0) {
                dexFile.refreshFull();
                byte[] outBytes = dexFile.getBytes();
                module.add(new ByteInputSource(outBytes, dexName));
                totalApplied += applied;
                if (logger != null) {
                    logger.ok("DEX 类型已改名", "DEX types renamed",
                            dexName + " count=" + applied);
                }
            } else if (logger != null) {
                logger.item("此 DEX 无匹配类型", "No matching types in DEX", dexName);
            }
            try {
                dexFile.close();
            } catch (Exception ignored) {
            }
        }
        return totalApplied;
    }

    public static int rewriteAbsoluteManifestRefs(
            ApkModule module,
            String oldPackage,
            String newPackage,
            SimpleApkLogger logger
    ) {
        AndroidManifestBlock m = ManifestOps.requireManifest(module);
        int changed = 0;

        ResXmlElement application = m.getApplicationElement();
        if (application != null) {
            if (rewriteNameAttr(application, oldPackage, newPackage)) changed++;
        }

        String[] tags = new String[]{
                AndroidManifest.TAG_activity,
                AndroidManifest.TAG_activity_alias,
                AndroidManifest.TAG_service,
                AndroidManifest.TAG_receiver,
                AndroidManifest.TAG_provider,
                "instrumentation"
        };
        ResXmlElement manifestEl = m.getManifestElement();
        for (String tag : tags) {
            List<ResXmlElement> list = m.listApplicationElementsByTag(tag);
            if ((list == null || list.isEmpty())
                    && "instrumentation".equals(tag)
                    && manifestEl != null) {
                list = new ArrayList<>();
                Iterator<ResXmlElement> it = manifestEl.getElements(tag);
                while (it != null && it.hasNext()) {
                    list.add(it.next());
                }
            }
            if (list == null) continue;
            for (ResXmlElement el : list) {
                if (rewriteNameAttr(el, oldPackage, newPackage)) changed++;
                if (AndroidManifest.TAG_provider.equals(tag)) {
                    if (rewriteAuthorities(el, oldPackage, newPackage)) changed++;
                }
                if (AndroidManifest.TAG_activity_alias.equals(tag)) {
                    if (rewriteTargetActivity(el, oldPackage, newPackage)) changed++;
                }
            }
        }

        if (changed > 0) {
            m.refresh();
            module.setManifest(m);
            if (logger != null) {
                logger.ok("Manifest 绝对引用已改写", "Manifest absolute refs rewritten",
                        String.valueOf(changed));
            }
        }
        return changed;
    }

    private static boolean rewriteNameAttr(ResXmlElement el, String oldPkg, String newPkg) {
        ResXmlAttribute attr = el.searchAttributeByResourceId(AndroidManifest.ID_name);
        if (attr == null) attr = el.searchAttributeByName(AndroidManifest.NAME_name);
        if (attr == null || attr.getValueType() != ValueType.STRING) return false;
        String name = attr.getValueAsString();
        String next = rewriteTypeName(name, oldPkg, newPkg);
        if (next == null || next.equals(name)) return false;
        attr.setValueAsString(next);
        return true;
    }

    private static boolean rewriteTargetActivity(ResXmlElement el, String oldPkg, String newPkg) {
        ResXmlAttribute attr = el.searchAttributeByResourceId(AndroidManifest.ID_targetActivity);
        if (attr == null) {
            attr = el.searchAttributeByName("targetActivity");
        }
        if (attr == null || attr.getValueType() != ValueType.STRING) return false;
        String name = attr.getValueAsString();
        String next = rewriteTypeName(name, oldPkg, newPkg);
        if (next == null || next.equals(name)) return false;
        attr.setValueAsString(next);
        return true;
    }

    private static boolean rewriteAuthorities(ResXmlElement el, String oldPkg, String newPkg) {
        ResXmlAttribute attr = el.searchAttributeByResourceId(AndroidManifest.ID_authorities);
        if (attr == null) {
            attr = el.searchAttributeByName("authorities");
        }
        if (attr == null || attr.getValueType() != ValueType.STRING) return false;
        String value = attr.getValueAsString();
        if (value == null) return false;
        String[] parts = value.split(";");
        boolean any = false;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            String n = rewriteAuthority(p, oldPkg, newPkg);
            if (!n.equals(p)) any = true;
            if (i > 0) sb.append(';');
            sb.append(n);
        }
        if (!any) return false;
        attr.setValueAsString(sb.toString());
        return true;
    }

    /** Absolute class under old package → new package; relative names unchanged. */
    static String rewriteTypeName(String name, String oldPkg, String newPkg) {
        if (name == null) return null;
        String n = name.trim();
        if (n.isEmpty()) return n;
        if (n.equals(oldPkg)) return newPkg;
        if (n.startsWith(oldPkg + ".")) {
            return newPkg + n.substring(oldPkg.length());
        }
        return n;
    }

    static String rewriteAuthority(String authority, String oldPkg, String newPkg) {
        if (authority == null) return null;
        String a = authority.trim();
        if (a.equals(oldPkg)) return newPkg;
        if (a.startsWith(oldPkg + ".")) {
            return newPkg + a.substring(oldPkg.length());
        }
        return a;
    }

    private static int countDex(ApkModule module) {
        try {
            List<DexFileInputSource> list = module.listDexFiles();
            return list == null ? 0 : list.size();
        } catch (Exception e) {
            return 0;
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) {
            if (n > 0) bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }
}
