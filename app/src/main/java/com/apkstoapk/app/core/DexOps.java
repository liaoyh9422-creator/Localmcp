package com.apkstoapk.app.core;

import com.apkstoapk.app.util.SimpleApkLogger;
import com.reandroid.apk.ApkModule;

import java.util.ArrayList;
import java.util.List;

/**
 * Thin low-level facade over {@link DexPatcher}.
 * Not wired into UI / {@link ApksMerger}.
 */
public final class DexOps {
    private DexOps() {}

    public static DexPatcher.Result applyDefault(ApkModule module, SimpleApkLogger logger)
            throws Exception {
        return DexPatcher.applyDefault(module, logger);
    }

    public static DexPatcher.Result apply(
            ApkModule module,
            List<DexPatcher.Target> clearTargets,
            boolean removeInvokeP,
            boolean injectLoadLibrary,
            SimpleApkLogger logger
    ) throws Exception {
        return DexPatcher.apply(module, logger, clearTargets, removeInvokeP, injectLoadLibrary);
    }

    /** Clear only the given methods to return-void; no invoke-p / loadLibrary side strategies. */
    public static DexPatcher.Result clearMethodsOnly(
            ApkModule module,
            List<DexPatcher.Target> targets,
            SimpleApkLogger logger
    ) throws Exception {
        List<DexPatcher.Target> list = targets == null
                ? new ArrayList<DexPatcher.Target>()
                : targets;
        return DexPatcher.apply(module, logger, list, false, false);
    }

    public static DexPatcher.Target target(String classDescriptor, String methodName, String proto) {
        return new DexPatcher.Target(classDescriptor, methodName, proto);
    }

    public static List<DexPatcher.Target> defaultClearTargets() {
        return DexPatcher.defaultClearTargets();
    }

    /** True package rename across all classes*.dex (ARSCLib RenameTypes). */
    public static PackageRenameOps.Result renamePackage(
            ApkModule module,
            String newPackageName,
            SimpleApkLogger logger
    ) throws Exception {
        return PackageRenameOps.rename(module, newPackageName, logger);
    }

    public static int renamePackageTypesOnly(
            ApkModule module,
            String oldPackage,
            String newPackage,
            SimpleApkLogger logger
    ) throws Exception {
        return PackageRenameOps.renameDexPackages(module, oldPackage, newPackage, logger);
    }
}