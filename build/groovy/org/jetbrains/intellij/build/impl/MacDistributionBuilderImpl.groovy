/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.intellij.build.impl

import org.jetbrains.intellij.build.BuildContext

import java.time.LocalDate
/**
 * @author nik
 */
class MacDistributionBuilderImpl {
  private final BuildContext buildContext
  final String macDistPath

  MacDistributionBuilderImpl(BuildContext buildContext) {
    this.buildContext = buildContext
    macDistPath = "$buildContext.paths.buildOutputRoot/dist.mac"
  }

  public layoutMac(File ideaPropertiesFile) {
    def docTypes = buildContext.productProperties.mac.docTypes ?: """
      <dict>
        <key>CFBundleTypeExtensions</key>
        <array>
          <string>ipr</string>
        </array>
        <key>CFBundleTypeIconFile</key>
        <string>${buildContext.fileNamePrefix}.icns</string>
        <key>CFBundleTypeName</key>
        <string>${buildContext.applicationInfo.productName} Project File</string>
        <key>CFBundleTypeRole</key>
        <string>Editor</string>
      </dict>
"""
    Map<String, String> customIdeaProperties = ["idea.jre.check": "$buildContext.productProperties.toolsJarRequired"];
    layoutMacApp(ideaPropertiesFile, customIdeaProperties, docTypes)
    buildContext.productProperties.customMacLayout(macDistPath)
    buildMacZip()
  }

  private void layoutMacApp(File ideaPropertiesFile, Map<String, String> customIdeaProperties, String docTypes) {
    String target = macDistPath
    def macProductProperties = buildContext.productProperties.mac
    buildContext.ant.copy(todir: "$target/bin") {
      fileset(dir: "$buildContext.paths.communityHome/bin/mac")
    }

    buildContext.ant.copy(todir: target) {
      fileset(dir: "$buildContext.paths.communityHome/build/conf/mac/Contents")
    }

    String executable = buildContext.fileNamePrefix
    String icns = "idea.icns" //todo[nik] rename to more generic name?
    String helpId = macProductProperties.helpId
    String helpIcns = "$target/Resources/${helpId}.help/Contents/Resources/Shared/product.icns"
    String customIcns = buildContext.productProperties.icns
    if (customIcns != null) {
      buildContext.ant.delete(file: "$target/Resources/idea.icns")
      buildContext.ant.copy(file: customIcns, todir: "$target/Resources")
      buildContext.ant.copy(file: customIcns, tofile: helpIcns)
      icns = new File(customIcns).name
    }
    else {
      buildContext.ant.copy(file: "$target/Resources/idea.icns", tofile: helpIcns)
    }

    String fullName = buildContext.applicationInfo.productName

    //todo[nik] why do we put vm options to separate places (some into Info.plist, some into vmoptions file)?
    String vmOptions = "-Dfile.encoding=UTF-8 ${VmOptionsGenerator.computeCommonVmOptions(buildContext.applicationInfo.isEAP)} -Xverify:none"

    //todo[nik] improve
    String minor = buildContext.applicationInfo.minorVersion
    boolean isNotRelease = buildContext.applicationInfo.isEAP && !minor.contains("RC") && !minor.contains("Beta")
    String version = isNotRelease ? "EAP $buildContext.fullBuildNumber" : "${buildContext.applicationInfo.majorVersion}.${minor}"
    String EAP = isNotRelease ? "-EAP" : ""

    //todo[nik] don't mix properties for idea.properties file with properties for Info.plist
    Map<String, String> properties = readIdeaProperties(ideaPropertiesFile, customIdeaProperties)

    def coreKeys = ["idea.platform.prefix", "idea.paths.selector", "idea.executable"]

    String coreProperties = submapToXml(properties, coreKeys);

    StringBuilder effectiveProperties = new StringBuilder()
    properties.each { k, v ->
      if (!coreKeys.contains(k)) {
        effectiveProperties.append("$k=$v\n");
      }
    }

    new File("$target/bin/idea.properties").text = effectiveProperties.toString()
    String ideaVmOptions = "${VmOptionsGenerator.vmOptionsForArch(JvmArchitecture.x64)} -XX:+UseCompressedOops"
    if (buildContext.applicationInfo.isEAP && buildContext.productProperties.includeYourkitAgentInEAP && macProductProperties.includeYourkitAgentInEAP) {
      ideaVmOptions += VmOptionsGenerator.yourkitOptions(buildContext.systemSelector, "")
    }
    new File("$target/bin/${executable}.vmoptions").text = ideaVmOptions.split(" ").join("\n")

    String classPath = buildContext.bootClassPathJarNames.collect { "\$APP_PACKAGE/Contents/lib/${it}" }.join(":")

    String archsString = """
    <key>LSArchitecturePriority</key>
    <array>"""
    macProductProperties.architectures.each {
      archsString += "<string>$it</string>"
    }
    archsString += "</array>\n"

    List<String> urlSchemes = macProductProperties.urlSchemes
    String urlSchemesString = ""
    if (urlSchemes.size() > 0) {
      urlSchemesString += """
      <key>CFBundleURLTypes</key>
      <array>
        <dict>
          <key>CFBundleTypeRole</key>
          <string>Editor</string>
          <key>CFBundleURLName</key>
          <string>Stacktrace</string>
          <key>CFBundleURLSchemes</key>
          <array>
"""
      urlSchemes.each { scheme ->
        urlSchemesString += "            <string>${scheme}</string>"
      }
      urlSchemesString += """
          </array>
        </dict>
      </array>
"""
    }

    String todayYear = LocalDate.now().year
    buildContext.ant.replace(file: "$target/Info.plist") {
      replacefilter(token: "@@build@@", value: buildContext.fullBuildNumber)
      replacefilter(token: "@@doc_types@@", value: docTypes ?: "")
      replacefilter(token: "@@executable@@", value: executable)
      replacefilter(token: "@@icns@@", value: icns)
      replacefilter(token: "@@bundle_name@@", value: fullName)
      replacefilter(token: "@@product_state@@", value: EAP)
      replacefilter(token: "@@bundle_identifier@@", value: macProductProperties.bundleIdentifier)
      replacefilter(token: "@@year@@", value: "$todayYear")
      replacefilter(token: "@@company_name@@", value: buildContext.applicationInfo.companyName)
      replacefilter(token: "@@min_year@@", value: "2000")
      replacefilter(token: "@@max_year@@", value: "$todayYear")
      replacefilter(token: "@@version@@", value: version)
      replacefilter(token: "@@vmoptions@@", value: vmOptions)
      replacefilter(token: "@@idea_properties@@", value: coreProperties)
      replacefilter(token: "@@class_path@@", value: classPath)
      replacefilter(token: "@@help_id@@", value: helpId)
      replacefilter(token: "@@url_schemes@@", value: urlSchemesString)
      replacefilter(token: "@@archs@@", value: archsString)
      replacefilter(token: "@@min_osx@@", value: macProductProperties.minOSXVersion)
    }

    if (executable != "idea") {
      buildContext.ant.move(file: "$target/MacOS/idea", tofile: "$target/MacOS/$executable")
    }

    buildContext.ant.replace(file: "$target/bin/inspect.sh") {
      replacefilter(token: "@@product_full@@", value: fullName)
      replacefilter(token: "@@script_name@@", value: executable)
    }
    String inspectScript = buildContext.productProperties.customInspectScriptName
    if (inspectScript != null && inspectScript != "inspect") {
      buildContext.ant.move(file: "$target/bin/inspect.sh", tofile: "$target/bin/${inspectScript}.sh")
    }

    buildContext.ant.fixcrlf(srcdir: "$target/bin", includes: "*.sh", eol: "unix")
    buildContext.ant.fixcrlf(srcdir: "$target/bin", includes: "*.py", eol: "unix")
  }

  void buildMacZip() {
    buildContext.messages.block("Build zip archive for Mac OS") {
      def extraBins = buildContext.productProperties.mac.extraMacBins
      def allPaths = [buildContext.paths.distAll, macDistPath]
      def zipRoot = buildContext.productProperties.macAppRoot(buildContext.applicationInfo, buildContext.buildNumber)
      def targetPath = "$buildContext.paths.artifacts/${buildContext.productProperties.archiveName(buildContext.buildNumber)}.mac.zip"
      buildContext.ant.zip(zipfile: targetPath) {
        allPaths.each {
          zipfileset(dir: it, prefix: zipRoot) {
            exclude(name: "bin/*.sh")
            exclude(name: "bin/*.py")
            exclude(name: "bin/fsnotifier")
            exclude(name: "bin/restarter")
            exclude(name: "MacOS/*")
            exclude(name: "build.txt")
            exclude(name: "NOTICE.txt")
            extraBins.each {
              exclude(name: it)
            }
            exclude(name: "bin/idea.properties")
          }
        }

        allPaths.each {
          zipfileset(dir: it, filemode: "755", prefix: zipRoot) {
            include(name: "bin/*.sh")
            include(name: "bin/*.py")
            include(name: "bin/fsnotifier")
            include(name: "bin/restarter")
            include(name: "MacOS/*")
            extraBins.each {
              include(name: it)
            }
          }
        }

        allPaths.each {
          zipfileset(dir: it, prefix: "$zipRoot/Resources") {
            include(name: "build.txt")
            include(name: "NOTICE.txt")
          }
        }

        zipfileset(file: "$macDistPath/bin/idea.properties", prefix: "$zipRoot/bin")
      }
      buildContext.notifyArtifactBuilt(targetPath)
    }
  }

  private static String submapToXml(Map<String, String> properties, List<String> keys) {
// generate properties description for Info.plist
    StringBuilder buff = new StringBuilder()

    keys.each { key ->
      String value = properties[key]
      if (value != null) {
        String string =
          """
        <key>$key</key>
        <string>$value</string>
"""
        buff.append(string)
      }
    }
    return buff.toString()
  }

  /**
   * E.g.
   *
   * Load all properties from file:
   *    readIdeaProperties(buildContext, "$home/ruby/build/idea.properties")
   *
   * Load all properties except "idea.cycle.buffer.size", change "idea.max.intellisense.filesize" to 3000
   * and enable "idea.is.internal" mode:
   *    readIdeaProperties(buildContext, "$home/ruby/build/idea.properties",
   *                       "idea.properties" : ["idea.max.intellisense.filesize" : 3000,
   *                                           "idea.cycle.buffer.size" : null,
   *                                           "idea.is.internal" : true ])
   * @param args
   * @return text xml properties description in xml
   */
  private Map<String, String> readIdeaProperties(File propertiesFile, Map<String, String> customProperties = [:]) {
    Map<String, String> ideaProperties = [:]
    propertiesFile.withReader {
      Properties loadedProperties = new Properties();
      loadedProperties.load(it)
      ideaProperties.putAll(loadedProperties as Map<String, String>)
    }

    Map<String, String> properties =
      ["CVS_PASSFILE"                          : "~/.cvspass",
       "com.apple.mrj.application.live-resize" : "false",
       "idea.paths.selector"                   : buildContext.systemSelector,
       "idea.executable"                       : buildContext.fileNamePrefix,
       "java.endorsed.dirs"                    : "",
       "idea.smooth.progress"                  : "false",
       "apple.laf.useScreenMenuBar"            : "true",
       "apple.awt.graphics.UseQuartz"          : "true",
       "apple.awt.fullscreencapturealldisplays": "false"]
    if (buildContext.productProperties.platformPrefix != null) {
      properties["idea.platform.prefix"] = buildContext.productProperties.platformPrefix
    }

    properties += customProperties

    properties.each { k, v ->
      if (v == null) {
        // if overridden with null - ignore property
        ideaProperties.remove(k)
      }
      else {
        // if property is overridden in args map - use new value
        ideaProperties[k] = v
      }
    }

    return ideaProperties
  }
}