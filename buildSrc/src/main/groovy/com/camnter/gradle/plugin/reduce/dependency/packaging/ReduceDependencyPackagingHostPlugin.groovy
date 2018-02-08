package com.camnter.gradle.plugin.reduce.dependency.packaging

import com.android.build.gradle.AppExtension
import com.android.build.gradle.AppPlugin
import com.android.build.gradle.api.ApplicationVariant
import com.android.build.gradle.internal.api.ApplicationVariantImpl
import com.android.build.gradle.internal.pipeline.TransformTask
import com.android.build.gradle.internal.res.GenerateLibraryRFileTask
import com.android.build.gradle.internal.res.LinkApplicationAndroidResourcesTask
import com.android.build.gradle.internal.transforms.ProGuardTransform
import com.android.build.gradle.tasks.ProcessAndroidResources
import com.camnter.gradle.plugin.reduce.dependency.packaging.utils.FileUtil
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.plugins.ExtensionContainer

/**
 * Refer from VirtualAPK
 *
 * @author CaMnter
 */

class ReduceDependencyPackagingHostPlugin implements Plugin<Project> {

    Project project

    /**
     * Stores files generated by the host side and is used when building plugin apk
     * */
    private def hostDir

    @Override
    void apply(Project project) {
        this.project = project

        final ExtensionContainer extensions = project.extensions
        if (!project.plugins.hasPlugin(AppPlugin.class)) {
            println "[ReduceDependencyPackagingHostPlugin]   reduce-dependency-packaging-plugin requires the Android plugin to be configured"
            return
        }
        final AppExtension android = extensions.getByType(AppExtension.class)
        this.hostDir = new File(project.getBuildDir(), "reduceDependencyPackagingHost")
        project.afterEvaluate {
            android.applicationVariants.each { ApplicationVariant variant ->
                ApplicationVariantImpl applicationVariantImpl = variant as ApplicationVariantImpl
                generateDependencies(applicationVariantImpl)
                backupHostR(applicationVariantImpl)
                backupProguardMapping(applicationVariantImpl)
                // TODO open
                // keepResourceIds(applicationVariantImpl)
            }
        }
    }

    /**
     * Generate ${project.buildDir}/reduceDependencyPackagingHost/versions.txt
     *
     * @param applicationVariant variant
     */
    def generateDependencies(ApplicationVariantImpl variant) {
        variant.javaCompile.doLast {

            FileUtil.saveFile(this.hostDir, 'allVersions') {
                final List<String> deps = new ArrayList<String>()
                this.project.configurations.each {
                    String configName = it.name

                    if (!it.canBeResolved) {
                        deps.add("${configName} -> NOT READY")
                        return
                    }

                    it.resolvedConfiguration.resolvedArtifacts.each {
                        deps.add(
                                "${configName} -> id: ${it.moduleVersion.id}, type: ${it.type}, ext: ${it.extension}")
                    }
                }
                Collections.sort(deps)
                return deps
            }

            FileUtil.saveFile(this.hostDir, 'versions') {
                final List<String> deps = new ArrayList<String>()
                final Configuration compileClasspath = variant.variantData.variantDependency.compileClasspath
                compileClasspath.resolvedConfiguration.resolvedArtifacts.each {
                    deps.add("${it.moduleVersion.id} ${it.file.length()}")
                }
                Collections.sort(deps)
                return deps
            }
        }
    }

    /**
     * Save R symbol file
     *
     * @param applicationVariantImpl variant
     */
    def backupHostR(ApplicationVariantImpl variant) {
        final ProcessAndroidResources aaptTask = this.project.tasks.getByName(
                "process${variant.name.capitalize()}Resources")
        final File textSymbolOutputFile
        if (aaptTask instanceof LinkApplicationAndroidResourcesTask) {
            textSymbolOutputFile =
                    (aaptTask as LinkApplicationAndroidResourcesTask).textSymbolOutputFile
        } else if (aaptTask instanceof GenerateLibraryRFileTask) {
            textSymbolOutputFile = (aaptTask as GenerateLibraryRFileTask).textSymbolOutputFile
        }
        aaptTask.doLast {
            project.copy {
                from textSymbolOutputFile
                into hostDir
                rename { "Host_R.txt" }
            }
        }
    }

    def backupProguardMapping(ApplicationVariantImpl variant) {
        if (variant.buildType.minifyEnabled) {
            final TransformTask proguardTask = project.tasks
            ["transformClassesAndResourcesWithProguardFor${variant.name.capitalize()}"]

            final ProGuardTransform proguardTransform = proguardTask.transform
            final File mappingFile = proguardTransform.mappingFile

            proguardTask.doLast {
                project.copy {
                    from mappingFile
                    into hostDir
                }
            }
        }
    }

    /**
     * Keep the host app resource id same with last publish, in order to compatible with the published plugin
     * */
    def keepResourceIds(ApplicationVariantImpl variant) {

        def VIRTUAL_APK_DIR = new File([project.rootDir, 'virtualapk'].join(File.separator))
        System.println("keepResource start")
        def mergeResourceTask = project.tasks["merge${variant.name.capitalize()}Resources"]
        def vaDir = new File(VIRTUAL_APK_DIR, "${variant.dirName}")

        def rSymbole = new File(vaDir, 'Host-R.txt')
        if (!rSymbole.exists()) {
            return
        }

        File resDir = new File(project.projectDir, ['src', 'main', 'res'].join(File.separator))
        File mergedValuesDir = new File(mergeResourceTask.outputDir, 'values')

        mergeResourceTask.doFirst {
            generateIdsXml(rSymbole, resDir)
        }

        mergeResourceTask.doLast {

            def mergeXml = new File(variant.mergeResources.incrementalFolder, 'merger.xml')
            def typeEntries = [:] as Map<String, Set>

            collectResourceEntries(mergeXml, resDir.path, typeEntries)

            generatePublicXml(rSymbole, mergedValuesDir, typeEntries)

            new File(resDir, 'values/ids.xml').delete()
        }
    }

    def collectResourceEntries(final File mergeXml, final String projectResDir,
            final Map typeEntries) {

        collectAarResourceEntries(null, projectResDir, mergeXml, typeEntries)

        File aarDir = new File(project.buildDir, "intermediates/exploded-aar")

        project.configurations.compile.resolvedConfiguration.resolvedArtifacts.each {
            if (it.extension == 'aar') {
                def moduleVersion = it.moduleVersion.id
                def resPath = new File(aarDir,
                        "${moduleVersion.group}/${moduleVersion.name}/${moduleVersion.version}/res")
                collectAarResourceEntries(moduleVersion.version, resPath.path, mergeXml,
                        typeEntries)
            }
        }
    }

    def collectAarResourceEntries(String aarVersion, String resPath, File mergeXml,
            final Map typeEntries) {
        final def merger = new XmlParser().parse(mergeXml)
        def filter = aarVersion == null ? {
            it.@config == 'main' || it.@config == 'release'
        } : {
            it.@config = aarVersion
        }
        def dataSets = merger.dataSet.findAll filter
        dataSets.each {
            it.source.each {
                if (it.@path != resPath) {
                    return
                }
                it.file.each {
                    def String type = it.@type
                    if (type != null) {
                        def entrySet = getEntriesSet(type, typeEntries)
                        if (!entrySet.contains(it.@name)) {
                            entrySet.add(it.@name)
                        }
                    } else {
                        it.children().each {
                            type = it.name()
                            def name = it.@name
                            if (type.endsWith('-array')) {
                                type = 'array'
                            } else if (type == 'item') {
                                type = it.@type
                            } else if (type == 'declare-styleable') {
                                return
                            }
                            def entrySet = getEntriesSet(type, typeEntries)
                            if (!entrySet.contains(name)) {
                                entrySet.add(name)
                            }
                        }
                    }
                }
            }
        }
    }

    def generatePublicXml(rSymboleFile, destDir, hostResourceEntries) {
        def styleNameMap = [:] as Map
        def styleEntries = hostResourceEntries['style']
        styleEntries.each {
            def _styleName = it.replaceAll('\\.', '_')
            styleNameMap.put(_styleName, it)
        }

        def lastSplitType
        new File(destDir, "public.xml").withPrintWriter { pw ->
            pw.println "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            pw.println "<resources>"
            rSymboleFile.eachLine { line ->
                def values = line.split(' ')
                def type = values[1]
                if (type == 'styleable') {
                    return
                }
                if (type == 'style') {
                    if (styleNameMap.containsKey(values[2])) {
                        pw.println "\t<public type=\"${type}\" name=\"${styleNameMap.get(values[2])}\" id=\"${values[3]}\" />"
                    }
                    return
                }
                //ID does not filter and remains redundant
                if (type == 'id') {
                    pw.println "\t<public type=\"${type}\" name=\"${values[2]}\" id=\"${values[3]}\" />"
                    return
                }

                //Only keep resources' Id that are present in the current project
                Set entries = hostResourceEntries[type]
                if (entries != null && entries.contains(values[2])) {
                    pw.println "\t<public type=\"${type}\" name=\"${values[2]}\" id=\"${values[3]}\" />"
                } else {
                    if (entries == null) {
                        if (type != lastSplitType) {
                            lastSplitType = type
                            println ">>>> ${type} is splited"
                        }
                    } else {
                        if (type != 'attr') {
                            println ">>>> ${type} : ${values[2]} is deleted"
                        }
                    }
                }
            }
            pw.print "</resources>"
        }
    }

    def generateIdsXml(rSymboleFile, resDir) {
        new File(resDir, "values/ids.xml").withPrintWriter { pw ->
            pw.println "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            pw.println "<resources>"
            rSymboleFile.eachLine { line ->
                def values = line.split(' ')
                if (values[1] == 'id') pw.println "\t<item type=\"id\" name=\"${values[2]}\"/>"
            }
            pw.print "</resources>"
        }
    }

    def Set<String> getEntriesSet(final String type, final Map typeEntries) {
        def entries = typeEntries[type]
        if (entries == null) {
            entries = [] as Set<String>
            typeEntries[type] = entries
        }
        return entries
    }
}