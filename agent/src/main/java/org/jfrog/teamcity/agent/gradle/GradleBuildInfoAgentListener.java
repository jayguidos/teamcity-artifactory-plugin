package org.jfrog.teamcity.agent.gradle;

import com.google.common.collect.ImmutableMap;
import jetbrains.buildServer.ExtensionHolder;
import jetbrains.buildServer.agent.*;
import jetbrains.buildServer.util.ArchiveUtil;
import jetbrains.buildServer.util.EventDispatcher;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jfrog.build.api.BuildInfoConfigProperties;
import org.jfrog.build.client.ArtifactoryClientConfiguration;
import org.jfrog.gradle.plugin.artifactory.task.BuildInfoBaseTask;
import org.jfrog.teamcity.agent.release.ReleaseParameters;
import org.jfrog.teamcity.agent.util.ArtifactoryClientConfigurationBuilder;
import org.jfrog.teamcity.common.ConstantValues;
import org.jfrog.teamcity.common.RunTypeUtils;
import org.jfrog.teamcity.common.RunnerParameterKeys;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import static org.jfrog.teamcity.common.ConstantValues.PROP_SKIP_LOG_MESSAGE;
import static org.jfrog.teamcity.common.RunnerParameterKeys.URL;

/**
 * Applies build info extractor specific behavior (if activated) to gradle builds
 *
 * @author Noam Y. Tenne
 */
public class GradleBuildInfoAgentListener extends AgentLifeCycleAdapter {

    private ExtensionHolder extensionsLocator;

    public GradleBuildInfoAgentListener(@NotNull EventDispatcher<AgentLifeCycleListener> dispatcher,
                                        @NotNull ExtensionHolder extensionsLocator) {
        this.extensionsLocator = extensionsLocator;
        dispatcher.addListener(this);
    }

    @Override
    public void beforeRunnerStart(@NotNull BuildRunnerContext runner) {
        super.beforeRunnerStart(runner);

        Map<String, String> runnerParameters = runner.getRunnerParameters();
        String runType = runner.getRunType();

        if (RunTypeUtils.isGradleWithExtractorActivated(runType, runnerParameters)) {

            String selectedServerUrl = runnerParameters.get(URL);
            boolean validServerUrl = StringUtils.isNotBlank(selectedServerUrl);

            //Don't run if no server was configured
            AgentRunningBuild build = runner.getBuild();
            BuildProgressLogger logger = build.getBuildLogger();
            if (!validServerUrl) {
                String skipLogMessage = runnerParameters.get(PROP_SKIP_LOG_MESSAGE);
                if (StringUtils.isNotBlank(skipLogMessage)) {
                    logger.warning(skipLogMessage);
                }
            } else {
                if (Boolean.valueOf(runnerParameters.get(RunnerParameterKeys.ENABLE_RELEASE_MANAGEMENT))) {
                    File workingDirectory = runner.getWorkingDirectory();
                    File gradleProperties = new File(workingDirectory, ConstantValues.GRADLE_PROPERTIES_FILE_NAME);
                    if (!gradleProperties.exists()) {
                        String errorMessage = "Unable to find the gradle release management properties file at '" +
                                gradleProperties.getAbsolutePath() + "'.";
                        logger.error(errorMessage);
                        logger.buildFailureDescription(errorMessage);
                        logger.exception(new FileNotFoundException(errorMessage));
                        return;
                    }

                    ArtifactsPublisher publisher =
                            extensionsLocator.getExtensions(ArtifactsPublisher.class).iterator().next();
                    File gradlePropertiesPacked;
                    try {
                        gradlePropertiesPacked = ArchiveUtil.packFile(gradleProperties);
                    } catch (IOException e) {
                        String errorMessage = "Unable to publish the '" + ConstantValues.GRADLE_PROPERTIES_FILE_NAME +
                                "' file to the server: " + e.getMessage();
                        logger.error(errorMessage);
                        logger.buildFailureDescription(errorMessage);
                        logger.exception(e);
                        return;
                    }
                    publisher.publishFiles(ImmutableMap.of(gradlePropertiesPacked, ".teamcity"));
                }

                addBuildInfoTaskAndInitScript(runner);
            }
        }
    }

    private void addBuildInfoTaskAndInitScript(BuildRunnerContext runnerContext) {
        AgentRunningBuild build = runnerContext.getBuild();

        Map<String, String> runnerParameters = runnerContext.getRunnerParameters();
        ArtifactoryClientConfiguration clientConf = ArtifactoryClientConfigurationBuilder.create(runnerContext);
        try {

            ReleaseParameters releaseParams = new ReleaseParameters(build);
            if (releaseParams.isReleaseBuild()) {
                clientConf.info.setReleaseEnabled(true);
                String comment = releaseParams.getStagingComment();
                if (StringUtils.isNotBlank(comment)) {
                    clientConf.info.setReleaseComment(comment);
                }
            }

            StringBuilder commandBuilder = new StringBuilder();
            String existingCommand = runnerParameters.get("ui.gradleRunner.additional.gradle.cmd.params");
            if (StringUtils.isNotBlank(existingCommand)) {
                commandBuilder.append(existingCommand).append(" ");
            }

            if (!Boolean.valueOf(runnerParameters.get(RunnerParameterKeys.PROJECT_USES_ARTIFACTORY_GRADLE_PLUGIN))) {
                File tempInitScript = createAndGetInitScript(build);
                if (tempInitScript != null) {
                    commandBuilder.append("-I ").append(tempInitScript.getCanonicalPath()).append(" ");
                }
            }

            StringBuilder taskBuilder = new StringBuilder();
            String existingTasks = runnerParameters.get("ui.gradleRunner.gradle.tasks.names");
            if (StringUtils.isNotBlank(existingTasks)) {
                taskBuilder.append(existingTasks).append(" ");
            }
            taskBuilder.append(BuildInfoBaseTask.BUILD_INFO_TASK_NAME);
            runnerContext.addRunnerParameter("ui.gradleRunner.gradle.tasks.names", taskBuilder.toString());
            File tempPropFile = File.createTempFile("buildInfo", "properties");
            clientConf.setPropertiesFile(tempPropFile.getCanonicalPath());
            clientConf.persistToPropertiesFile();
            commandBuilder.append("-D").append(BuildInfoConfigProperties.PROP_PROPS_FILE).append("=").
                    append(tempPropFile.getCanonicalPath());
            runnerContext.addRunnerParameter("ui.gradleRunner.additional.gradle.cmd.params", commandBuilder.toString());
        } catch (IOException e) {
            BuildProgressLogger logger = build.getBuildLogger();
            logger.warning("An error occurred while creating the gradle build info init script. " +
                    "Build-info task will not be added.");
            logger.exception(e);
        }
    }

    private File createAndGetInitScript(AgentRunningBuild build) throws IOException {
        BuildProgressLogger logger = build.getBuildLogger();
        InputStream initScriptStream = getClass().getClassLoader().getResourceAsStream("initscripttemplate.gradle");

        String scriptTemplate;
        try {
            scriptTemplate = IOUtils.toString(initScriptStream);
        } catch (IOException e) {
            logger.warning("An error occurred while loading the gradle init script template. " +
                    "Build-info task will not be added.");
            logger.exception(e);
            return null;
        } finally {
            IOUtils.closeQuietly(initScriptStream);
        }

        File pluginsDirectory = build.getAgentConfiguration().getAgentPluginsDirectory();
        File pluginLibDir = new File(pluginsDirectory, ConstantValues.NAME + File.separator + "lib");
        try {
            String normalizedPath = FilenameUtils.separatorsToUnix(pluginLibDir.getCanonicalPath());
            scriptTemplate = scriptTemplate.replace("${pluginLibDir}", normalizedPath);
        } catch (IOException e) {
            logger.warning("An error occurred while locating the gradle plugin dependencies folder. " +
                    "Build-info task will not be added.");
            logger.exception(e);
            return null;
        }

        File tempInitScript = File.createTempFile("artifactory.init.script", "gradle");
        FileUtils.writeStringToFile(tempInitScript, scriptTemplate, "utf-8");
        return tempInitScript;
    }
}
