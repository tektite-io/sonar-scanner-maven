/*
 * SonarQube Scanner for Maven
 * Copyright (C) 2009-2025 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonarsource.scanner.maven;

import com.google.common.annotations.VisibleForTesting;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.lifecycle.LifecycleExecutor;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginManagement;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.rtinfo.RuntimeInformation;
import org.apache.maven.toolchain.ToolchainManager;
import org.sonarsource.scanner.lib.EnvironmentConfig;
import org.sonarsource.scanner.lib.ScannerEngineBootstrapper;
import org.sonarsource.scanner.lib.ScannerProperties;
import org.sonarsource.scanner.maven.bootstrap.MavenCompilerResolver;
import org.sonarsource.scanner.maven.bootstrap.MavenProjectConverter;
import org.sonarsource.scanner.maven.bootstrap.PropertyDecryptor;
import org.sonarsource.scanner.maven.bootstrap.ScannerBootstrapper;
import org.sonarsource.scanner.maven.bootstrap.ScannerBootstrapperFactory;
import org.sonatype.plexus.components.sec.dispatcher.SecDispatcher;

/**
 * Analyze project. SonarQube server must be started.
 */
@Mojo(name = "sonar", requiresDependencyResolution = ResolutionScope.TEST, aggregator = true)

public class SonarQubeMojo extends AbstractMojo {

  @Parameter(defaultValue = "${session}", required = true, readonly = true)
  private MavenSession session;

  /**
   * Set this to 'true' to skip analysis.
   *
   * @since 2.3
   */
  @Parameter(alias = "sonar.skip", property = "sonar.skip", defaultValue = "false")
  private boolean skip;

  @Component
  private LifecycleExecutor lifecycleExecutor;

  @Component(hint = "mng-4384")
  private SecDispatcher securityDispatcher;

  @Component
  private RuntimeInformation runtimeInformation;

  @Parameter(defaultValue = "${mojoExecution}", required = true, readonly = true)
  private MojoExecution mojoExecution;

  @Component
  private ToolchainManager toolchainManager;

  // Visible for testing
  Map<String, String> environmentVariables = new HashMap<>(System.getenv());

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {

    if (shouldDelayExecution()) {
      getLog().info("Delaying SonarQube Scanner to the end of multi-module project");
      return;
    }

    warnAboutUnspecifiedSonarPluginVersion();

    Map<String, String> envProps = EnvironmentConfig.load(environmentVariables);

    MavenCompilerResolver mavenCompilerResolver = new MavenCompilerResolver(session, lifecycleExecutor, getLog(), toolchainManager);
    MavenProjectConverter mavenProjectConverter = new MavenProjectConverter(getLog(), mavenCompilerResolver, envProps);

    PropertyDecryptor propertyDecryptor = new PropertyDecryptor(getLog(), securityDispatcher);

    ScannerBootstrapperFactory bootstrapperFactory = new ScannerBootstrapperFactory(getLog(), runtimeInformation, mojoExecution, session, envProps, propertyDecryptor);

    if (isSkip(bootstrapperFactory.createGlobalProperties())) {
      return;
    }

    ScannerEngineBootstrapper engineBootstrapper = bootstrapperFactory.create();
    ScannerBootstrapper scannerBootstrapper = new ScannerBootstrapper(getLog(), session, engineBootstrapper, mavenProjectConverter, propertyDecryptor);
    scannerBootstrapper.execute();
  }

  private void warnAboutUnspecifiedSonarPluginVersion() {
    String effectivePluginVersion = mojoExecution.getVersion();
    String groupId = mojoExecution.getGroupId();
    String artifactId = mojoExecution.getArtifactId();
    Plugin plugin = mojoExecution.getPlugin();
    MavenProject project = session.getTopLevelProject();
    List<String> goals = session.getGoals();
    boolean requiredArgumentsAreNotNull = !Arrays.asList(effectivePluginVersion, groupId, artifactId, plugin, project, goals).contains(null);
    if (requiredArgumentsAreNotNull) {
      String invalidPluginVersion = null;
      String configuredPluginVersion = plugin.getVersion();
      if ("LATEST".equals(configuredPluginVersion) || "RELEASE".equals(configuredPluginVersion)) {
        invalidPluginVersion = configuredPluginVersion;
      } else if (!isPluginVersionDefinedInTheProject(project, groupId, artifactId) && isVersionMissingFromSonarGoal(goals, groupId, artifactId)) {
        invalidPluginVersion = "an unspecified version";
      }
      if (invalidPluginVersion != null) {
        getLog().warn(String.format("Using %s instead of an explicit plugin version may introduce breaking analysis changes at an unwanted time. " +
          "It is highly recommended to use an explicit version, e.g. '%s:%s:%s'.", invalidPluginVersion, groupId, artifactId, effectivePluginVersion));
      }
    }
  }

  @VisibleForTesting
  static boolean isPluginVersionDefinedInTheProject(MavenProject project, String groupId, String artifactId) {
    Stream<Plugin> pluginStream = project.getBuildPlugins().stream();
    PluginManagement pluginManagement = project.getPluginManagement();
    pluginStream = pluginManagement != null ? Stream.concat(pluginStream, pluginManagement.getPlugins().stream()) : pluginStream;
    return pluginStream.anyMatch(plugin -> (plugin.getGroupId() == null || groupId.equals(plugin.getGroupId())) &&
      artifactId.equals(plugin.getArtifactId()) &&
      (plugin.getVersion() != null && !plugin.getVersion().isBlank()));
  }

  private static boolean isVersionMissingFromSonarGoal(List<String> goals, String groupId, String artifactId) {
    List<String> sonarGoalsWithoutVersion = Arrays.asList("sonar:sonar", groupId + ":" + artifactId + ":sonar");
    return goals.stream().anyMatch(sonarGoalsWithoutVersion::contains);
  }

  /**
   * Should scanner be delayed?
   *
   * @return true if goal is attached to phase and not last in a multi-module project
   */
  private boolean shouldDelayExecution() {
    return !isDetachedGoal() && !isLastProjectInReactor();
  }

  /**
   * Is this execution a 'detached' goal run from the cli.  e.g. mvn sonar:sonar
   * <p>
   * See <a href="https://maven.apache.org/guides/mini/guide-default-execution-ids.html#Default_executionIds_for_Implied_Executions">
   * Default executionIds for Implied Executions</a>
   * for explanation of command line execution id.
   *
   * @return true if this execution is from the command line
   */
  private boolean isDetachedGoal() {
    return "default-cli".equals(mojoExecution.getExecutionId());
  }

  /**
   * Is this project the last project in the reactor?
   *
   * @return true if last project (including only project)
   */
  private boolean isLastProjectInReactor() {
    List<MavenProject> sortedProjects = session.getProjectDependencyGraph().getSortedProjects();

    MavenProject lastProject = sortedProjects.isEmpty()
      ? session.getCurrentProject()
      : sortedProjects.get(sortedProjects.size() - 1);

    if (getLog().isDebugEnabled()) {
      getLog().debug("Current project: '" + session.getCurrentProject().getName() +
        "', Last project to execute based on dependency graph: '" + lastProject.getName() + "'");
    }

    return session.getCurrentProject().equals(lastProject);
  }

  private boolean isSkip(Map<String, String> properties) {
    if (skip) {
      getLog().info("sonar.skip = true: Skipping analysis");
      return true;
    }

    if ("true".equalsIgnoreCase(properties.get(ScannerProperties.SKIP))) {
      getLog().info("SonarQube Scanner analysis skipped");
      return true;
    }
    return false;
  }

  MavenSession getSession() {
    return session;
  }

  MojoExecution getMojoExecution() {
    return mojoExecution;
  }
}
