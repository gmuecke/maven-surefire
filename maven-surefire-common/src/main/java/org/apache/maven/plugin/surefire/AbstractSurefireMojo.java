// CHECKSTYLE_OFF: FileLength|RegexpHeader
package org.apache.maven.plugin.surefire;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.metadata.ArtifactMetadataSource;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactNotFoundException;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.artifact.resolver.filter.ExcludesArtifactFilter;
import org.apache.maven.artifact.resolver.filter.ScopeArtifactFilter;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugin.surefire.booterclient.ChecksumCalculator;
import org.apache.maven.plugin.surefire.booterclient.ForkConfiguration;
import org.apache.maven.plugin.surefire.booterclient.ForkStarter;
import org.apache.maven.plugin.surefire.booterclient.ProviderDetector;
import org.apache.maven.plugin.surefire.util.DependencyScanner;
import org.apache.maven.plugin.surefire.util.DirectoryScanner;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.artifact.filter.PatternIncludesArtifactFilter;
import org.apache.maven.shared.utils.StringUtils;
import org.apache.maven.shared.utils.io.FileUtils;
import org.apache.maven.surefire.booter.ClassLoaderConfiguration;
import org.apache.maven.surefire.booter.Classpath;
import org.apache.maven.surefire.booter.ClasspathConfiguration;
import org.apache.maven.surefire.booter.KeyValueSource;
import org.apache.maven.surefire.booter.ProviderConfiguration;
import org.apache.maven.surefire.booter.ProviderParameterNames;
import org.apache.maven.surefire.booter.Shutdown;
import org.apache.maven.surefire.booter.StartupConfiguration;
import org.apache.maven.surefire.booter.SurefireBooterForkException;
import org.apache.maven.surefire.booter.SurefireExecutionException;
import org.apache.maven.surefire.cli.CommandLineOption;
import org.apache.maven.surefire.providerapi.SurefireProvider;
import org.apache.maven.surefire.report.ReporterConfiguration;
import org.apache.maven.surefire.suite.RunResult;
import org.apache.maven.surefire.testset.DirectoryScannerParameters;
import org.apache.maven.surefire.testset.RunOrderParameters;
import org.apache.maven.surefire.testset.TestArtifactInfo;
import org.apache.maven.surefire.testset.TestListResolver;
import org.apache.maven.surefire.testset.TestRequest;
import org.apache.maven.surefire.testset.TestSetFailedException;
import org.apache.maven.surefire.util.DefaultScanResult;
import org.apache.maven.surefire.util.RunOrder;
import org.apache.maven.toolchain.Toolchain;
import org.apache.maven.toolchain.ToolchainManager;

import javax.annotation.Nonnull;

/**
 * Abstract base class for running tests using Surefire.
 *
 * @author Stephen Connolly
 * @version $Id: SurefirePlugin.java 945065 2010-05-17 10:26:22Z stephenc $
 */
public abstract class AbstractSurefireMojo
    extends AbstractMojo
    implements SurefireExecutionParameters
{

    // common mojo parameters

    /**
     * Information about this plugin, mainly used to lookup this plugin's configuration from the currently executing
     * project.
     *
     * @since 2.12
     */
    @Parameter( defaultValue = "${plugin}", readonly = true )
    protected PluginDescriptor pluginDescriptor;

    /**
     * Set this to "true" to skip running tests, but still compile them. Its use is NOT RECOMMENDED, but quite
     * convenient on occasion.
     *
     * @since 2.4
     */
    @Parameter( property = "skipTests", defaultValue = "false" )
    protected boolean skipTests;

    /**
     * This old parameter is just like <code>skipTests</code>, but bound to the old property "maven.test.skip.exec".
     *
     * @since 2.3
     * @deprecated Use skipTests instead.
     */
    @Parameter( property = "maven.test.skip.exec" )
    protected boolean skipExec;

    /**
     * Set this to "true" to bypass unit tests entirely. Its use is NOT RECOMMENDED, especially if you enable it using
     * the "maven.test.skip" property, because maven.test.skip disables both running the tests and compiling the tests.
     * Consider using the <code>skipTests</code> parameter instead.
     */
    @Parameter( property = "maven.test.skip", defaultValue = "false" )
    protected boolean skip;

    /**
     * The Maven Project Object.
     */
    @Component
    protected MavenProject project;

    /**
     * The base directory of the project being tested. This can be obtained in your integration test via
     * System.getProperty("basedir").
     */
    @Parameter( defaultValue = "${basedir}" )
    protected File basedir;

    /**
     * The directory containing generated test classes of the project being tested. This will be included at the
     * beginning of the test classpath. *
     */
    @Parameter( defaultValue = "${project.build.testOutputDirectory}" )
    protected File testClassesDirectory;

    /**
     * List of dependencies to exclude from the test classpath. Each dependency string must follow the format
     * <i>groupId:artifactId</i>. For example: <i>org.acme:project-a</i>
     *
     * @since 2.6
     */
    @Parameter( property = "maven.test.dependency.excludes" )
    private String[] classpathDependencyExcludes;

    /**
     * A dependency scope to exclude from the test classpath. The scope should be one of the scopes defined by
     * org.apache.maven.artifact.Artifact. This includes the following:
     * <p/>
     * <ul>
     * <li><i>compile</i> - system, provided, compile
     * <li><i>runtime</i> - compile, runtime
     * <li><i>compile+runtime</i> - system, provided, compile, runtime
     * <li><i>runtime+system</i> - system, compile, runtime
     * <li><i>test</i> - system, provided, compile, runtime, test
     * </ul>
     *
     * @since 2.6
     */
    @Parameter( defaultValue = "" )
    private String classpathDependencyScopeExclude;

    /**
     * Additional elements to be appended to the classpath.
     *
     * @since 2.4
     */
    @Parameter( property = "maven.test.additionalClasspath" )
    private String[] additionalClasspathElements;

    /**
     * The test source directory containing test class sources.
     *
     * @since 2.2
     */
    @Parameter( defaultValue = "${project.build.testSourceDirectory}", required = true )
    protected File testSourceDirectory;

    /**
     * A list of &lt;exclude> elements specifying the tests (by pattern) that should be excluded in testing. When not
     * specified and when the <code>test</code> parameter is not specified, the default excludes will be <code><br/>
     * &lt;excludes><br/>
     * &nbsp;&lt;exclude>**&#47;*$*&lt;/exclude><br/>
     * &lt;/excludes><br/>
     * </code> (which excludes all inner classes).<br>
     * This parameter is ignored if the TestNG <code>suiteXmlFiles</code> parameter is specified.
     * <p/>
     * Each exclude item may also contain a comma-separated sublist of items, which will be treated as multiple
     * &nbsp;&lt;exclude> entries.<br/>
     * Since 2.19 a complex syntax is supported in one parameter (JUnit 4, JUnit 4.7+, TestNG):<br/>
     * <exclude>%regex[pkg.*Slow.*.class], Unstable*</exclude><br/>
     * <br/>
     * <em>Notice that</em> these values are relative to the directory containing generated test classes of the project
     * being tested. This directory is declared by the parameter <code>testClassesDirectory</code> which defaults
     * to the POM property <code>${project.build.testOutputDirectory}</code>, typically <em>src/test/java</em>
     * unless overridden.
     */
    @Parameter
    protected List<String> excludes;

    /**
     * ArtifactRepository of the localRepository. To obtain the directory of localRepository in unit tests use
     * System.getProperty("localRepository").
     */
    @Parameter( defaultValue = "${localRepository}", required = true, readonly = true )
    protected ArtifactRepository localRepository;

    /**
     * List of System properties to pass to the JUnit tests.
     *
     * @deprecated Use systemPropertyVariables instead.
     */
    @Parameter
    protected Properties systemProperties;

    /**
     * List of System properties to pass to the JUnit tests.
     *
     * @since 2.5
     */
    @Parameter
    protected Map<String, String> systemPropertyVariables;

    /**
     * List of System properties, loaded from a file, to pass to the JUnit tests.
     *
     * @since 2.8.2
     */
    @Parameter
    protected File systemPropertiesFile;

    /**
     * List of properties for configuring all TestNG related configurations. This is the new preferred method of
     * configuring TestNG.
     *
     * @since 2.4
     */
    @Parameter
    protected Properties properties;

    /**
     * Map of plugin artifacts.
     */
    // olamy: would make more sense using defaultValue but doesn't work with maven 2.x
    @Parameter( property = "plugin.artifactMap", required = true, readonly = true )
    protected Map<String, Artifact> pluginArtifactMap;

    /**
     * Map of project artifacts.
     */
    // olamy: would make more sense using defaultValue but doesn't work with maven 2.x
    @Parameter( property = "project.artifactMap", readonly = true, required = true )
    protected Map<String, Artifact> projectArtifactMap;

    /**
     * Add custom text into report filename: TEST-testClassName-reportNameSuffix.xml,
     * testClassName-reportNameSuffix.txt and testClassName-reportNameSuffix-output.txt.
     * File TEST-testClassName-reportNameSuffix.xml has changed attributes 'testsuite'--'name'
     * and 'testcase'--'classname' - reportNameSuffix is added to the attribute value.
     */
    @Parameter( property = "surefire.reportNameSuffix", defaultValue = "" )
    protected String reportNameSuffix;

    /**
     * Set this to "true" to redirect the unit test standard output to a file (found in
     * reportsDirectory/testName-output.txt).
     *
     * @since 2.3
     */
    @Parameter( property = "maven.test.redirectTestOutputToFile", defaultValue = "false" )
    protected boolean redirectTestOutputToFile;

    /**
     * Set this to "true" to cause a failure if there are no tests to run. Defaults to "false".
     *
     * @since 2.4
     */
    @Parameter( property = "failIfNoTests" )
    protected Boolean failIfNoTests;

    /**
     * <strong>DEPRECATED</strong> since version 2.14. Use <code>forkCount</code> and <code>reuseForks</code> instead.
     * <br/>
     * <br/>
     * Option to specify the forking mode. Can be "never", "once", "always", "perthread". "none" and "pertest" are also
     * accepted for backwards compatibility. "always" forks for each test-class. "perthread" will create
     * <code>threadCount</code> parallel forks, each executing one test-class. See also parameter
     * <code>reuseForks</code>.<br/>
     *
     * @since 2.1
     */
    @Parameter( property = "forkMode", defaultValue = "once" )
    protected String forkMode;

    /**
     * Option to specify the jvm (or path to the java executable) to use with the forking options. For the default, the
     * jvm will be a new instance of the same VM as the one used to run Maven. JVM settings are not inherited from
     * MAVEN_OPTS.
     *
     * @since 2.1
     */
    @Parameter( property = "jvm" )
    protected String jvm;

    /**
     * Arbitrary JVM options to set on the command line.
     * <br/>
     * <br/>
     * Using an alternate syntax for <em>argLine</em>, <pre>@{...}</pre> allows late replacement of properties when the
     * plugin is executed, so properties that have been modified by other plugins will be picked up correctly.
     * See the Frequently Asked Questions page with more details:<br/>
     * <a href="http://maven.apache.org/surefire/maven-surefire-plugin/faq.html">
     *     http://maven.apache.org/surefire/maven-surefire-plugin/faq.html</a>
     * <br/>
     * <a href="http://maven.apache.org/surefire/maven-failsafe-plugin/faq.html">
     *     http://maven.apache.org/surefire/maven-failsafe-plugin/faq.html</a>
     *
     * @since 2.1
     */
    @Parameter( property = "argLine" )
    protected String argLine;

    /**
     * Additional environment variables to set on the command line.
     *
     * @since 2.1.3
     */
    @Parameter
    protected Map<String, String> environmentVariables = new HashMap<String, String>();

    /**
     * Command line working directory.
     *
     * @since 2.1.3
     */
    @Parameter( property = "basedir" )
    protected File workingDirectory;

    /**
     * When false it makes tests run using the standard classloader delegation instead of the default Maven isolated
     * classloader. Only used when forking (forkMode is not "none").<br/>
     * Setting it to false helps with some problems caused by conflicts between xml parsers in the classpath and the
     * Java 5 provider parser.
     *
     * @since 2.1
     */
    @Parameter( property = "childDelegation", defaultValue = "false" )
    protected boolean childDelegation;

    /**
     * (TestNG/JUnit47 provider with JUnit4.8+ only) Groups for this test. Only classes/methods/etc decorated with one
     * of the groups specified here will be included in test run, if specified.<br/>
     * For JUnit, this parameter forces the use of the 4.7 provider<br/>
     * This parameter is ignored if the <code>suiteXmlFiles</code> parameter is specified.<br/>
     * Since version 2.18.1 and JUnit 4.12, the <em>@Category<em> annotation type is automatically inherited from
     * superclasses, see <em>@java.lang.annotation.Inherited</em>. Make sure that test class inheritance still makes
     * sense together with <em>@Category<em> annotation of the JUnit 4.12 or higher appeared in superclass.
     *
     * @since 2.2
     */
    @Parameter( property = "groups" )
    protected String groups;

    /**
     * (TestNG/JUnit47 provider with JUnit4.8+ only) Excluded groups. Any methods/classes/etc with one of the groups
     * specified in this list will specifically not be run.<br/>
     * For JUnit, this parameter forces the use of the 4.7 provider<br/>
     * This parameter is ignored if the <code>suiteXmlFiles</code> parameter is specified.<br/>
     * Since version 2.18.1 and JUnit 4.12, the <em>@Category<em> annotation type is automatically inherited from
     * superclasses, see <em>@java.lang.annotation.Inherited</em>. Make sure that test class inheritance still makes
     * sense together with <em>@Category<em> annotation of the JUnit 4.12 or higher appeared in superclass.
     *
     * @since 2.2
     */
    @Parameter( property = "excludedGroups" )
    protected String excludedGroups;

    /**
     * Allows you to specify the name of the JUnit artifact. If not set, <code>junit:junit</code> will be used.
     *
     * @since 2.3.1
     */
    @Parameter( property = "junitArtifactName", defaultValue = "junit:junit" )
    protected String junitArtifactName;

    /**
     * Allows you to specify the name of the TestNG artifact. If not set, <code>org.testng:testng</code> will be used.
     *
     * @since 2.3.1
     */
    @Parameter( property = "testNGArtifactName", defaultValue = "org.testng:testng" )
    protected String testNGArtifactName;

    /**
     * (TestNG/JUnit 4.7 provider) The attribute thread-count allows you to specify how many threads should be
     * allocated for this execution. Only makes sense to use in conjunction with the <code>parallel</code> parameter.
     *
     * @since 2.2
     */
    @Parameter( property = "threadCount" )
    protected int threadCount;

    /**
     * Option to specify the number of VMs to fork in parallel in order to execute the tests. When terminated with "C",
     * the number part is multiplied with the number of CPU cores. Floating point value are only accepted together with
     * "C". If set to "0", no VM is forked and all tests are executed within the main process.<br/>
     * <br/>
     * Example values: "1.5C", "4"<br/>
     * <br/>
     * The system properties and the <code>argLine</code> of the forked processes may contain the place holder string
     * <code>${surefire.forkNumber}</code>, which is replaced with a fixed number for each of the parallel forks,
     * ranging from <code>1</code> to the effective value of <code>forkCount</code> times the maximum number of parallel
     * Surefire executions in maven parallel builds, i.e. the effective value of the <code>-T</code> command line
     * argument of maven core.
     *
     * @since 2.14
     */
    @Parameter( property = "forkCount", defaultValue = "1" )
    private String forkCount;

    /**
     * Indicates if forked VMs can be reused. If set to "false", a new VM is forked for each test class to be executed.
     * If set to "true", up to <code>forkCount</code> VMs will be forked and then reused to execute all tests.
     *
     * @since 2.13
     */

    @Parameter( property = "reuseForks", defaultValue = "true" )
    private boolean reuseForks;

    /**
     * (JUnit 4.7 provider) Indicates that threadCount, threadCountSuites, threadCountClasses, threadCountMethods
     * are per cpu core.
     *
     * @since 2.5
     */
    @Parameter( property = "perCoreThreadCount", defaultValue = "true" )
    protected boolean perCoreThreadCount;

    /**
     * (JUnit 4.7 provider) Indicates that the thread pool will be unlimited. The <code>parallel</code> parameter and
     * the actual number of classes/methods will decide. Setting this to "true" effectively disables
     * <code>perCoreThreadCount</code> and <code>threadCount</code>. Defaults to "false".
     *
     * @since 2.5
     */
    @Parameter( property = "useUnlimitedThreads", defaultValue = "false" )
    protected boolean useUnlimitedThreads;

    /**
     * (TestNG provider) When you use the <code>parallel</code> attribute, TestNG will try to run all your test methods
     * in separate threads, except for methods that depend on each other, which will be run in the same thread in order
     * to respect their order of execution.
     * <p/>
     * (JUnit 4.7 provider) Supports values "classes"/"methods"/"both" to run in separate threads, as controlled by
     * <code>threadCount</code>.<br/>
     * <br/>
     * Since version 2.16 (JUnit 4.7 provider), the value "both" is <strong>DEPRECATED</strong>.
     * Use <strong>"classesAndMethods"</strong> instead.<br/>
     * <br/>
     * Since version 2.16 (JUnit 4.7 provider), additional vales are available
     * "suites"/"suitesAndClasses"/"suitesAndMethods"/"classesAndMethods"/"all".
     *
     * @since 2.2
     */
    @Parameter( property = "parallel" )
    protected String parallel;

    /**
     * (JUnit 4.7 / provider only) The thread counts do not exceed the number of parallel suite, class runners and
     * average number of methods per class if set to <strong>true</strong>.
     * <p/>
     * True by default.
     *
     * @since 2.17
     */
    @Parameter( property = "parallelOptimized", defaultValue = "true" )
    protected boolean parallelOptimized;

    /**
     * (JUnit 4.7 provider) This attribute allows you to specify the concurrency in test suites, i.e.:
     * <ul>
     *  <li>number of concurrent suites if <code>threadCount</code> is 0 or unspecified</li>
     *  <li>limited suites concurrency if <code>useUnlimitedThreads</code> is set to <strong>true</strong></li>
     *  <li>if <code>threadCount</code> and certain thread-count parameters are &gt; 0 for <code>parallel</code>, the
     *  concurrency is computed from ratio. For instance parallel=all and the ratio between
     *      <em>threadCountSuites</em>:<code>threadCountClasses</code>:<code>threadCountMethods</code> is
     *      <em>2</em>:3:5, there is 20% of <code>threadCount</code> in concurrent suites.</li>
     * </ul>
     *
     * Only makes sense to use in conjunction with the <code>parallel</code> parameter.
     * The default value <code>0</code> behaves same as unspecified one.
     *
     * @since 2.16
     */
    @Parameter( property = "threadCountSuites", defaultValue = "0" )
    protected int threadCountSuites;

    /**
     * (JUnit 4.7 provider) This attribute allows you to specify the concurrency in test classes, i.e.:
     * <ul>
     *  <li>number of concurrent classes if <code>threadCount</code> is 0 or unspecified</li>
     *  <li>limited classes concurrency if <code>useUnlimitedThreads</code> is set to <strong>true</strong></li>
     *  <li>if <code>threadCount</code> and certain thread-count parameters are &gt; 0 for <code>parallel</code>, the
     *  concurrency is computed from ratio. For instance parallel=all and the ratio between
     *      <code>threadCountSuites</code>:<em>threadCountClasses</em>:<code>threadCountMethods</code> is
     *      2:<em>3</em>:5, there is 30% of <code>threadCount</code> in concurrent classes.</li>
     *  <li>as in the previous case but without this leaf thread-count. Example: parallel=suitesAndClasses,
     *  threadCount=16, threadCountSuites=5, threadCountClasses is unspecified leaf, the number of concurrent classes
     *  is varying from &gt;= 11 to 14 or 15. The threadCountSuites become number of threads.
     *  </li>
     * </ul>
     *
     * Only makes sense to use in conjunction with the <code>parallel</code> parameter.
     * The default value <code>0</code> behaves same as unspecified one.
     *
     * @since 2.16
     */
    @Parameter( property = "threadCountClasses", defaultValue = "0" )
    protected int threadCountClasses;

    /**
     * (JUnit 4.7 provider) This attribute allows you to specify the concurrency in test methods, i.e.:
     * <ul>
     * <li>number of concurrent methods if <code>threadCount</code> is 0 or unspecified</li>
     * <li>limited concurrency of methods if <code>useUnlimitedThreads</code> is set to <strong>true</strong></li>
     * <li>if <code>threadCount</code> and certain thread-count parameters are &gt; 0 for <code>parallel</code>, the
     * concurrency is computed from ratio. For instance parallel=all and the ratio between
     * <code>threadCountSuites</code>:<code>threadCountClasses</code>:<em>threadCountMethods</em> is 2:3:<em>5</em>,
     * there is 50% of <code>threadCount</code> in concurrent methods.</li>
     * <li>as in the previous case but without this leaf thread-count. Example: parallel=all, threadCount=16,
     * threadCountSuites=2, threadCountClasses=3, but threadCountMethods is unspecified leaf, the number of concurrent
     * methods is varying from &gt;= 11 to 14 or 15. The threadCountSuites and threadCountClasses become number of
     * threads.</li>
     * </ul>
     * Only makes sense to use in conjunction with the <code>parallel</code> parameter. The default value <code>0</code>
     * behaves same as unspecified one.
     *
     * @since 2.16
     */
    @Parameter( property = "threadCountMethods", defaultValue = "0" )
    protected int threadCountMethods;

    /**
     * Whether to trim the stack trace in the reports to just the lines within the test, or show the full trace.
     *
     * @since 2.2
     */
    @Parameter( property = "trimStackTrace", defaultValue = "true" )
    protected boolean trimStackTrace;

    /**
     * Resolves the artifacts needed.
     */
    @Component
    protected ArtifactResolver artifactResolver;

    /**
     * Creates the artifact.
     */
    @Component
    protected ArtifactFactory artifactFactory;

    /**
     * The remote plugin repositories declared in the POM.
     *
     * @since 2.2
     */
    @Parameter( defaultValue = "${project.pluginArtifactRepositories}" )
    protected List<ArtifactRepository> remoteRepositories;

    /**
     * For retrieval of artifact's metadata.
     */
    @Component
    protected ArtifactMetadataSource metadataSource;

    /**
     * Flag to disable the generation of report files in xml format.
     *
     * @since 2.2
     */
    @Parameter( property = "disableXmlReport", defaultValue = "false" )
    protected boolean disableXmlReport;

    /**
     * By default, Surefire enables JVM assertions for the execution of your test cases. To disable the assertions, set
     * this flag to "false".
     *
     * @since 2.3.1
     */
    @Parameter( property = "enableAssertions", defaultValue = "true" )
    protected boolean enableAssertions;

    /**
     * The current build session instance.
     */
    @Component
    protected MavenSession session;

    /**
     * (TestNG only) Define the factory class used to create all test instances.
     *
     * @since 2.5
     */
    @Parameter( property = "objectFactory" )
    protected String objectFactory;

    /**
     *
     */
    @Parameter( defaultValue = "${session.parallel}", readonly = true )
    protected Boolean parallelMavenExecution;

    /**
     * List of dependencies to scan for test classes to include in the test run.
     * The child elements of this element must be &lt;dependency&gt; elements, and the
     * contents of each of these elements must be a string which follows the format:
     *
     * <i>groupId:artifactId</i>. For example: <i>org.acme:project-a</i>.
     *
     * @since 2.15
     */
    @Parameter( property = "dependenciesToScan" )
    private String[] dependenciesToScan;

    /**
     *
     */
    @Component
    protected ToolchainManager toolchainManager;

    private Artifact surefireBooterArtifact;

    private Toolchain toolchain;

    private int effectiveForkCount = -1;

    /**
     * The placeholder that is replaced by the executing thread's running number. The thread number
     * range starts with 1
     * Deprecated.
     */
    public static final String THREAD_NUMBER_PLACEHOLDER = "${surefire.threadNumber}";

    /**
     * The placeholder that is replaced by the executing fork's running number. The fork number
     * range starts with 1
     */
    public static final String FORK_NUMBER_PLACEHOLDER = "${surefire.forkNumber}";

    protected abstract String getPluginName();

    protected abstract int getRerunFailingTestsCount();

    public abstract List<String> getIncludes();

    public abstract File getIncludesFile();

    public abstract void setIncludes( List<String> includes );

    public abstract File getExcludesFile();

    public abstract File[] getSuiteXmlFiles();

    public abstract void setSuiteXmlFiles( File[] suiteXmlFiles );

    public abstract String getRunOrder();

    public abstract void setRunOrder( String runOrder );

    protected abstract void handleSummary( RunResult summary, Exception firstForkException )
        throws MojoExecutionException, MojoFailureException;

    protected abstract String[] getDefaultIncludes();

    protected abstract boolean isSkipExecution();

    private SurefireDependencyResolver dependencyResolver;

    private TestListResolver specificTests;

    private TestListResolver includedExcludedTests;

    private List<CommandLineOption> cli;

    public void execute()
        throws MojoExecutionException, MojoFailureException
    {
        cli = commandLineOptions();
        // Stuff that should have been final
        setupStuff();

        if ( verifyParameters() && !hasExecutedBefore() )
        {
            DefaultScanResult scan = scanForTestClasses();
            if ( !isValidSuiteXmlFileConfig() && scan.isEmpty() )
            {
                if ( getEffectiveFailIfNoTests() )
                {
                    throw new MojoFailureException(
                        "No tests were executed!  (Set -DfailIfNoTests=false to ignore this error.)" );
                }
                handleSummary( RunResult.noTestsRun(), null );
                return;
            }
            logReportsDirectory();
            executeAfterPreconditionsChecked( scan );
        }
    }

    private void setupStuff()
    {
        createDependencyResolver();
        surefireBooterArtifact = getSurefireBooterArtifact();
        toolchain = getToolchain();
    }

    private DefaultScanResult scanForTestClasses()
        throws MojoFailureException
    {
        DefaultScanResult scan = scanDirectories();
        DefaultScanResult scanDeps = scanDependencies();
        return scan.append( scanDeps );
    }

    private DefaultScanResult scanDirectories()
        throws MojoFailureException
    {
        DirectoryScanner scanner = new DirectoryScanner( getTestClassesDirectory(),
                                                         getIncludedAndExcludedTests(),
                                                         getSpecificTests() );
        return scanner.scan();
    }

    private DefaultScanResult scanDependencies()
    {
        if ( getDependenciesToScan() == null )
        {
            return null;
        }
        else
        {
            try
            {
                // @TODO noinspection unchecked, check MavenProject 3.x for Generics in surefire:3.0
                @SuppressWarnings( "unchecked" )
                List<File> dependenciesToScan =
                    DependencyScanner.filter( project.getTestArtifacts(), Arrays.asList( getDependenciesToScan() ) );
                DependencyScanner scanner = new DependencyScanner( dependenciesToScan,
                                                                   getIncludedAndExcludedTests(),
                                                                   getSpecificTests() );
                return scanner.scan();
            }
            catch ( Exception e )
            {
                throw new RuntimeException( e );
            }
        }
    }

    boolean verifyParameters()
        throws MojoFailureException, MojoExecutionException
    {
        setProperties( new SurefireProperties( getProperties() ) );
        if ( isSkipExecution() )
        {
            getLog().info( "Tests are skipped." );
            return false;
        }

        String jvmToUse = getJvm();
        if ( toolchain != null )
        {
            getLog().info( "Toolchain in maven-" + getPluginName() + "-plugin: " + toolchain );
            if ( jvmToUse != null )
            {
                getLog().warn( "Toolchains are ignored, 'executable' parameter is set to " + jvmToUse );
            }
        }

        if ( !getTestClassesDirectory().exists()
            && ( getDependenciesToScan() == null || getDependenciesToScan().length == 0 ) )
        {
            if ( Boolean.TRUE.equals( getFailIfNoTests() ) )
            {
                throw new MojoFailureException( "No tests to run!" );
            }
            getLog().info( "No tests to run." );
        }
        else
        {
            convertDeprecatedForkMode();
            ensureWorkingDirectoryExists();
            ensureParallelRunningCompatibility();
            ensureThreadCountWithPerThread();
            warnIfUselessUseSystemClassLoaderParameter();
            warnIfDefunctGroupsCombinations();
            warnIfRerunClashes();
            warnIfWrongShutdownValue();
            warnIfNotApplicableSkipAfterFailureCount();
        }
        return true;
    }

    private void executeAfterPreconditionsChecked( DefaultScanResult scanResult )
        throws MojoExecutionException, MojoFailureException
    {

        List<ProviderInfo> providers = createProviders();

        RunResult current = RunResult.noTestsRun();

        Exception firstForkException = null;
        for ( ProviderInfo provider : providers )
        {
            try
            {
                current = current.aggregate( executeProvider( provider, scanResult ) );
            }
            catch ( SurefireBooterForkException e )
            {
                if ( firstForkException == null )
                {
                    firstForkException = e;
                }
            }
            catch ( SurefireExecutionException e )
            {
                if ( firstForkException == null )
                {
                    firstForkException = e;
                }
            }
            catch ( TestSetFailedException e )
            {
                if ( firstForkException == null )
                {
                    firstForkException = e;
                }
            }
        }

        if ( firstForkException != null )
        {
            current = RunResult.failure( current, firstForkException );
        }

        handleSummary( current, firstForkException );
    }

    private void createDependencyResolver()
    {
        dependencyResolver =
            new SurefireDependencyResolver( getArtifactResolver(), getArtifactFactory(), getLog(), getLocalRepository(),
                                            getRemoteRepositories(), getMetadataSource(), getPluginName() );
    }

    protected List<ProviderInfo> createProviders()
        throws MojoFailureException, MojoExecutionException
    {
        Artifact junitDepArtifact = getJunitDepArtifact();
        return new ProviderList( new DynamicProviderInfo( null ),
                              new TestNgProviderInfo( getTestNgArtifact() ),
                              new JUnitCoreProviderInfo( getJunitArtifact(), junitDepArtifact ),
                              new JUnit4ProviderInfo( getJunitArtifact(), junitDepArtifact ),
                              new JUnit3ProviderInfo() )
            .resolve();
    }

    private SurefireProperties setupProperties()
    {
        SurefireProperties sysProps = null;
        try
        {
            sysProps = SurefireProperties.loadProperties( systemPropertiesFile );
        }
        catch ( IOException e )
        {
            String msg = "The system property file '" + systemPropertiesFile.getAbsolutePath() + "' can't be read.";
            if ( getLog().isDebugEnabled() )
            {
                getLog().warn( msg, e );
            }
            else
            {
                getLog().warn( msg );
            }
        }

        SurefireProperties result =
            SurefireProperties.calculateEffectiveProperties( getSystemProperties(), getSystemPropertyVariables(),
                                                             getUserProperties(), sysProps );

        result.setProperty( "basedir", getBasedir().getAbsolutePath() );
        result.setProperty( "user.dir", getWorkingDirectory().getAbsolutePath() );
        result.setProperty( "localRepository", getLocalRepository().getBasedir() );
        if ( isForking() )
        {
            for ( Object o : result.propertiesThatCannotBeSetASystemProperties() )
            {
                if ( getArgLine() == null || !getArgLine().contains( "-D" + o + "=" ) )
                {
                    getLog().warn( o + " cannot be set as system property, use <argLine>-D"
                                       + o + "=...</argLine> instead" );
                }
            }
            for ( Object systemPropertyMatchingArgLine : systemPropertiesMatchingArgLine( result ) )
            {
                getLog().warn( "The system property " + systemPropertyMatchingArgLine + " is configured twice! "
                                   + "The property appears in <argLine/> and any of <systemPropertyVariables/>, "
                                   + "<systemProperties/> or user property." );
            }
        }
        if ( getLog().isDebugEnabled() )
        {
            showToLog( result, getLog(), "system property" );
        }
        return result;
    }

    private Set<Object> systemPropertiesMatchingArgLine( SurefireProperties result )
    {
        Set<Object> intersection = new HashSet<Object>();
        if ( StringUtils.isNotBlank( getArgLine() ) )
        {
            for ( Object systemProperty : result.getStringKeySet() )
            {
                if ( getArgLine().contains( "-D" + systemProperty + "=" ) )
                {
                    intersection.add( systemProperty );
                }
            }

            Set<Object> ignored = result.propertiesThatCannotBeSetASystemProperties();
            intersection.removeAll( ignored );
        }
        return intersection;
    }

    public void showToLog( SurefireProperties props, org.apache.maven.plugin.logging.Log log, String setting )
    {
        for ( Object key : props.getStringKeySet() )
        {
            String value = props.getProperty( (String) key );
            log.debug( "Setting " + setting + " [" + key + "]=[" + value + "]" );
        }
    }


    private RunResult executeProvider( ProviderInfo provider, DefaultScanResult scanResult )
        throws MojoExecutionException, MojoFailureException, SurefireExecutionException, SurefireBooterForkException,
        TestSetFailedException
    {
        SurefireProperties effectiveProperties = setupProperties();
        ClassLoaderConfiguration classLoaderConfiguration = getClassLoaderConfiguration();
        provider.addProviderProperties();
        RunOrderParameters runOrderParameters =
            new RunOrderParameters( getRunOrder(), getStatisticsFileName( getConfigChecksum() ) );

        if ( isNotForking() )
        {
            createCopyAndReplaceForkNumPlaceholder( effectiveProperties, 1 ).copyToSystemProperties();

            InPluginVMSurefireStarter surefireStarter =
                createInprocessStarter( provider, classLoaderConfiguration, runOrderParameters );
            return surefireStarter.runSuitesInProcess( scanResult );
        }
        else
        {
            ForkConfiguration forkConfiguration = getForkConfiguration();
            if ( getLog().isDebugEnabled() )
            {
                showMap( getEnvironmentVariables(), "environment variable" );
            }

            Properties originalSystemProperties = (Properties) System.getProperties().clone();
            try
            {
                ForkStarter forkStarter =
                    createForkStarter( provider, forkConfiguration, classLoaderConfiguration, runOrderParameters,
                                       getLog() );
                return forkStarter.run( effectiveProperties, scanResult );
            }
            finally
            {
                System.setProperties( originalSystemProperties );
                cleanupForkConfiguration( forkConfiguration );
            }
        }
    }

    public static SurefireProperties createCopyAndReplaceForkNumPlaceholder(
        SurefireProperties effectiveSystemProperties, int threadNumber )
    {
        SurefireProperties filteredProperties = new SurefireProperties( ( KeyValueSource) effectiveSystemProperties );
        String threadNumberString = String.valueOf( threadNumber );
        for ( Entry<Object, Object> entry : effectiveSystemProperties.entrySet() )
        {
            if ( entry.getValue() instanceof String )
            {
                String value = (String) entry.getValue();
                value = value.replace( THREAD_NUMBER_PLACEHOLDER, threadNumberString );
                value = value.replace( FORK_NUMBER_PLACEHOLDER, threadNumberString );

                filteredProperties.put( entry.getKey(), value );
            }
        }
        return filteredProperties;
    }

    protected void cleanupForkConfiguration( ForkConfiguration forkConfiguration )
    {
        if ( !getLog().isDebugEnabled() && forkConfiguration != null )
        {
            File tempDirectory = forkConfiguration.getTempDirectory();
            try
            {
                FileUtils.deleteDirectory( tempDirectory );
            }
            catch ( IOException ioe )
            {
                getLog().warn( "Could not delete temp directory " + tempDirectory + " because " + ioe.getMessage() );
            }
        }
    }

    protected void logReportsDirectory()
    {
        logDebugOrCliShowErrors(
            StringUtils.capitalizeFirstLetter( getPluginName() ) + " report directory: " + getReportsDirectory() );
    }

    final Toolchain getToolchain()
    {
        Toolchain tc = null;

        if ( getToolchainManager() != null )
        {
            tc = getToolchainManager().getToolchainFromBuildContext( "jdk", getSession() );
        }

        return tc;
    }

    /**
     * Converts old TestNG configuration parameters over to new properties based configuration
     * method. (if any are defined the old way)
     */
    private void convertTestNGParameters() throws MojoExecutionException
    {
        if ( this.getParallel() != null )
        {
            getProperties().setProperty( ProviderParameterNames.PARALLEL_PROP, this.getParallel() );
        }
        convertGroupParameters();

        if ( this.getThreadCount() > 0 )
        {
            getProperties().setProperty( ProviderParameterNames.THREADCOUNT_PROP,
                                         Integer.toString( this.getThreadCount() ) );
        }
        if ( this.getObjectFactory() != null )
        {
            getProperties().setProperty( "objectfactory", this.getObjectFactory() );
        }
        if ( this.getTestClassesDirectory() != null )
        {
            getProperties().setProperty( "testng.test.classpath", getTestClassesDirectory().getAbsolutePath() );
        }

        Artifact testNgArtifact = getTestNgArtifact();
        if ( testNgArtifact != null )
        {
            DefaultArtifactVersion defaultArtifactVersion = new DefaultArtifactVersion( testNgArtifact.getVersion() );
            getProperties().setProperty( "testng.configurator", getConfiguratorName( defaultArtifactVersion ) );
        }
    }

    private static String getConfiguratorName( ArtifactVersion version )
        throws MojoExecutionException
    {
        try
        {
            VersionRange range = VersionRange.createFromVersionSpec( "[4.7,5.1]" );
            if ( range.containsVersion( version ) )
            {
                return "org.apache.maven.surefire.testng.conf.TestNG4751Configurator";
            }
            range = VersionRange.createFromVersionSpec( "[5.2]" );
            if ( range.containsVersion( version ) )
            {
                return "org.apache.maven.surefire.testng.conf.TestNG52Configurator";
            }
            range = VersionRange.createFromVersionSpec( "[5.3,5.9]" );
            if ( range.containsVersion( version ) )
            {
                return "org.apache.maven.surefire.testng.conf.TestNGMapConfigurator";
            }
            range = VersionRange.createFromVersionSpec( "[5.10,6.4]" );
            if ( range.containsVersion( version ) )
            {
                return "org.apache.maven.surefire.testng.conf.TestNG510Configurator";
            }
            range = VersionRange.createFromVersionSpec( "[6.5,)" );
            if ( range.containsVersion( version ) )
            {
                return "org.apache.maven.surefire.testng.conf.TestNG652Configurator";
            }

            throw new MojoExecutionException( "Unknown TestNG version " + version );
        }
        catch ( InvalidVersionSpecificationException invsex )
        {
            throw new MojoExecutionException( "Bug in plugin. Please report it with the attached stacktrace", invsex );
        }
    }


    private void convertGroupParameters()
    {
        if ( this.getExcludedGroups() != null )
        {
            getProperties().setProperty( ProviderParameterNames.TESTNG_EXCLUDEDGROUPS_PROP, this.getExcludedGroups() );
        }
        if ( this.getGroups() != null )
        {
            getProperties().setProperty( ProviderParameterNames.TESTNG_GROUPS_PROP, this.getGroups() );
        }
    }

    protected boolean isAnyConcurrencySelected()
    {
        return this.getParallel() != null && this.getParallel().trim().length() > 0;
    }

    protected boolean isAnyGroupsSelected()
    {
        return this.getGroups() != null || this.getExcludedGroups() != null;
    }

    /**
     * Converts old JUnit configuration parameters over to new properties based configuration
     * method. (if any are defined the old way)
     */
    private void convertJunitCoreParameters() throws MojoExecutionException
    {
        checkThreadCountEntity( getThreadCountSuites(), "suites" );
        checkThreadCountEntity( getThreadCountClasses(), "classes" );
        checkThreadCountEntity( getThreadCountMethods(), "methods" );

        String usedParallel = ( getParallel() != null ) ? getParallel() : "none";

        if ( !"none".equals( usedParallel ) )
        {
            checkNonForkedThreads( parallel );
        }

        getProperties().setProperty( ProviderParameterNames.PARALLEL_PROP, usedParallel );
        getProperties().setProperty( ProviderParameterNames.THREADCOUNT_PROP, Integer.toString( getThreadCount() ) );
        getProperties().setProperty( "perCoreThreadCount", Boolean.toString( getPerCoreThreadCount() ) );
        getProperties().setProperty( "useUnlimitedThreads", Boolean.toString( getUseUnlimitedThreads() ) );
        getProperties().setProperty( ProviderParameterNames.THREADCOUNTSUITES_PROP,
                                     Integer.toString( getThreadCountSuites() ) );
        getProperties().setProperty( ProviderParameterNames.THREADCOUNTCLASSES_PROP,
                                     Integer.toString( getThreadCountClasses() ) );
        getProperties().setProperty( ProviderParameterNames.THREADCOUNTMETHODS_PROP,
                                     Integer.toString( getThreadCountMethods() ) );
        getProperties().setProperty( ProviderParameterNames.PARALLEL_TIMEOUT_PROP,
                                     Double.toString( getParallelTestsTimeoutInSeconds() ) );
        getProperties().setProperty( ProviderParameterNames.PARALLEL_TIMEOUTFORCED_PROP,
                                     Double.toString( getParallelTestsTimeoutForcedInSeconds() ) );
        getProperties().setProperty( ProviderParameterNames.PARALLEL_OPTIMIZE_PROP,
                                     Boolean.toString( isParallelOptimized() ) );

        String message = "parallel='" + usedParallel + '\''
            + ", perCoreThreadCount=" + getPerCoreThreadCount()
            + ", threadCount=" + getThreadCount()
            + ", useUnlimitedThreads=" + getUseUnlimitedThreads()
            + ", threadCountSuites=" + getThreadCountSuites()
            + ", threadCountClasses=" + getThreadCountClasses()
            + ", threadCountMethods=" + getThreadCountMethods()
            + ", parallelOptimized=" + isParallelOptimized();

        logDebugOrCliShowErrors( message );
    }

    private void checkNonForkedThreads( String parallel ) throws MojoExecutionException
    {
        if ( "suites".equals( parallel ) )
        {
            if ( !( getUseUnlimitedThreads() || getThreadCount() > 0 ^ getThreadCountSuites() > 0 ) )
            {
                throw new MojoExecutionException(
                        "Use threadCount or threadCountSuites > 0 or useUnlimitedThreads=true for parallel='suites'" );
            }
            setThreadCountClasses( 0 );
            setThreadCountMethods( 0 );
        }
        else if ( "classes".equals( parallel ) )
        {
            if ( !( getUseUnlimitedThreads() || getThreadCount() > 0 ^ getThreadCountClasses() > 0 ) )
            {
                throw new MojoExecutionException(
                        "Use threadCount or threadCountClasses > 0 or useUnlimitedThreads=true for parallel='classes'"
                      );
            }
            setThreadCountSuites( 0 );
            setThreadCountMethods( 0 );
        }
        else if ( "methods".equals( parallel ) )
        {
            if ( !( getUseUnlimitedThreads() || getThreadCount() > 0 ^ getThreadCountMethods() > 0 ) )
            {
                throw new MojoExecutionException(
                        "Use threadCount or threadCountMethods > 0 or useUnlimitedThreads=true for parallel='methods'"
                      );
            }
            setThreadCountSuites( 0 );
            setThreadCountClasses( 0 );
        }
        else if ( "suitesAndClasses".equals( parallel ) )
        {
            if ( !( getUseUnlimitedThreads()
                    || onlyThreadCount()
                    || getThreadCountSuites() > 0 && getThreadCountClasses() > 0
                        && getThreadCount() == 0 && getThreadCountMethods() == 0
                    || getThreadCount() > 0 && getThreadCountSuites() > 0 && getThreadCountClasses() > 0
                        && getThreadCountMethods() == 0
                    || getThreadCount() > 0 && getThreadCountSuites() > 0 && getThreadCount() > getThreadCountSuites()
                        && getThreadCountClasses() == 0 && getThreadCountMethods() == 0 ) )
            {
                throw new MojoExecutionException( "Use useUnlimitedThreads=true, "
                        + "or only threadCount > 0, "
                        + "or (threadCountSuites > 0 and threadCountClasses > 0), "
                        + "or (threadCount > 0 and threadCountSuites > 0 and threadCountClasses > 0) "
                        + "or (threadCount > 0 and threadCountSuites > 0 and threadCount > threadCountSuites) "
                        + "for parallel='suitesAndClasses' or 'both'" );
            }
            setThreadCountMethods( 0 );
        }
        else if ( "suitesAndMethods".equals( parallel ) )
        {
            if ( !( getUseUnlimitedThreads()
                    || onlyThreadCount()
                    || getThreadCountSuites() > 0 && getThreadCountMethods() > 0
                        && getThreadCount() == 0 && getThreadCountClasses() == 0
                    || getThreadCount() > 0 && getThreadCountSuites() > 0 && getThreadCountMethods() > 0
                        && getThreadCountClasses() == 0
                    || getThreadCount() > 0 && getThreadCountSuites() > 0 && getThreadCount() > getThreadCountSuites()
                        && getThreadCountClasses() == 0 && getThreadCountMethods() == 0 ) )
            {
                throw new MojoExecutionException( "Use useUnlimitedThreads=true, "
                        + "or only threadCount > 0, "
                        + "or (threadCountSuites > 0 and threadCountMethods > 0), "
                        + "or (threadCount > 0 and threadCountSuites > 0 and threadCountMethods > 0), "
                        + "or (threadCount > 0 and threadCountSuites > 0 and threadCount > threadCountSuites) "
                        + "for parallel='suitesAndMethods'" );
            }
            setThreadCountClasses( 0 );
        }
        else if ( "both".equals( parallel ) || "classesAndMethods".equals( parallel ) )
        {
            if ( !( getUseUnlimitedThreads()
                    || onlyThreadCount()
                    || getThreadCountClasses() > 0 && getThreadCountMethods() > 0
                        && getThreadCount() == 0 && getThreadCountSuites() == 0
                    || getThreadCount() > 0 && getThreadCountClasses() > 0 && getThreadCountMethods() > 0
                        && getThreadCountSuites() == 0
                    || getThreadCount() > 0 && getThreadCountClasses() > 0 && getThreadCount() > getThreadCountClasses()
                        && getThreadCountSuites() == 0 && getThreadCountMethods() == 0 ) )
            {
                throw new MojoExecutionException( "Use useUnlimitedThreads=true, "
                        + "or only threadCount > 0, "
                        + "or (threadCountClasses > 0 and threadCountMethods > 0), "
                        + "or (threadCount > 0 and threadCountClasses > 0 and threadCountMethods > 0), "
                        + "or (threadCount > 0 and threadCountClasses > 0 and threadCount > threadCountClasses) "
                        + "for parallel='both' or parallel='classesAndMethods'" );
            }
            setThreadCountSuites( 0 );
        }
        else if ( "all".equals( parallel ) )
        {
            if ( !( getUseUnlimitedThreads()
                    || onlyThreadCount()
                    || getThreadCountSuites() > 0 && getThreadCountClasses() > 0 && getThreadCountMethods() > 0
                    || getThreadCount() > 0 && getThreadCountSuites() > 0 && getThreadCountClasses() > 0
                        && getThreadCountMethods() == 0
                        && getThreadCount() > ( getThreadCountSuites() + getThreadCountClasses() ) ) )
            {
                throw new MojoExecutionException( "Use useUnlimitedThreads=true, "
                        + "or only threadCount > 0, "
                        + "or (threadCountSuites > 0 and threadCountClasses > 0 and threadCountMethods > 0), "
                        + "or every thread-count is specified, "
                        + "or (threadCount > 0 and threadCountSuites > 0 and threadCountClasses > 0 "
                        + "and threadCount > threadCountSuites + threadCountClasses) "
                        + "for parallel='all'" );
            }
        }
        else
        {
            throw new MojoExecutionException( "Illegal parallel='" + parallel + "'" );
        }
    }

    private boolean onlyThreadCount()
    {
        return getThreadCount() > 0 && getThreadCountSuites() == 0 && getThreadCountClasses() == 0
                && getThreadCountMethods() == 0;
    }

    private static void checkThreadCountEntity( int count, String entity )
        throws MojoExecutionException
    {
        if ( count < 0 )
        {
            throw new MojoExecutionException(
                    "parallel maven execution does not allow negative thread-count" + entity );
        }
    }

    private boolean isJunit47Compatible( Artifact artifact )
    {
        return dependencyResolver.isWithinVersionSpec( artifact, "[4.7,)" );
    }

    private boolean isAnyJunit4( Artifact artifact )
    {
        return dependencyResolver.isWithinVersionSpec( artifact, "[4.0,)" );
    }

    static boolean isForkModeNever( String forkMode )
    {
        return ForkConfiguration.FORK_NEVER.equals( forkMode );
    }

    protected boolean isForking()
    {
        return 0 < getEffectiveForkCount();
    }

    String getEffectiveForkMode()
    {
        String forkMode1 = getForkMode();

        if ( toolchain != null && isForkModeNever( forkMode1 ) )
        {
            return ForkConfiguration.FORK_ONCE;
        }

        return ForkConfiguration.getEffectiveForkMode( forkMode1 );
    }

    private List<RunOrder> getRunOrders()
    {
        String runOrderString = getRunOrder();
        RunOrder[] runOrder = runOrderString == null ? RunOrder.DEFAULT : RunOrder.valueOfMulti( runOrderString );
        return Arrays.asList( runOrder );
    }

    private boolean requiresRunHistory()
    {
        final List<RunOrder> runOrders = getRunOrders();
        return runOrders.contains( RunOrder.BALANCED ) || runOrders.contains( RunOrder.FAILEDFIRST );
    }

    private boolean getEffectiveFailIfNoTests()
    {
        if ( isSpecificTestSpecified() )
        {
            if ( getFailIfNoSpecifiedTests() != null )
            {
                return getFailIfNoSpecifiedTests();
            }
            else if ( getFailIfNoTests() != null )
            {
                return getFailIfNoTests();
            }
            else
            {
                return true;
            }
        }
        else
        {
            return getFailIfNoTests() != null && getFailIfNoTests();
        }
    }

    private ProviderConfiguration createProviderConfiguration( RunOrderParameters runOrderParameters )
        throws MojoExecutionException, MojoFailureException
    {
        ReporterConfiguration reporterConfiguration =
            new ReporterConfiguration( getReportsDirectory(), isTrimStackTrace() );

        Artifact testNgArtifact = getTestNgArtifact();
        DirectoryScannerParameters directoryScannerParameters = null;
        final boolean isTestNg = testNgArtifact != null;
        TestArtifactInfo testNg =
            isTestNg ? new TestArtifactInfo( testNgArtifact.getVersion(), testNgArtifact.getClassifier() ) : null;
        List<File> testXml = getSuiteXmlFiles() != null ? Arrays.asList( getSuiteXmlFiles() ) : null;
        TestRequest testSuiteDefinition = new TestRequest( testXml, getTestSourceDirectory(), getSpecificTests(),
                                                           getRerunFailingTestsCount() );

        final boolean actualFailIfNoTests;

        if ( isValidSuiteXmlFileConfig() && !isSpecificTestSpecified() )
        {
            actualFailIfNoTests = getFailIfNoTests() != null && getFailIfNoTests();
            if ( !isTestNg )
            {
                throw new MojoExecutionException( "suiteXmlFiles is configured, but there is no TestNG dependency" );
            }
        }
        else
        {
            if ( isSpecificTestSpecified() )
            {
                actualFailIfNoTests = getEffectiveFailIfNoTests();
                setFailIfNoTests( actualFailIfNoTests );
            }
            else
            {
                actualFailIfNoTests = getFailIfNoTests() != null && getFailIfNoTests();
            }

            // @todo remove these three params and use DirectoryScannerParameters to pass into DirectoryScanner only
            // @todo or remove it in next major version :: 3.0
            // @todo remove deprecated methods in ProviderParameters => included|excluded|specificTests not needed here

            List<String> actualIncludes = getIncludeList(); // Collections.emptyList(); behaves same
            List<String> actualExcludes = getExcludeList(); // Collections.emptyList(); behaves same
            // Collections.emptyList(); behaves same
            List<String> specificTests = new ArrayList<String>( getSpecificTests().getTestSpecificClasses() );

            directoryScannerParameters =
                new DirectoryScannerParameters( getTestClassesDirectory(), actualIncludes, actualExcludes,
                                                specificTests, actualFailIfNoTests, getRunOrder() );
        }

        Map<String, String> providerProperties = toStringProperties( getProperties() );

        return new ProviderConfiguration( directoryScannerParameters, runOrderParameters, actualFailIfNoTests,
                                          reporterConfiguration,
                                          testNg, // Not really used in provider. Limited to de/serializer.
                                          testSuiteDefinition, providerProperties, null,
                                          false, cli, getSkipAfterFailureCount(),
                                          Shutdown.parameterOf( getShutdown() ) );
    }

    private static Map<String, String> toStringProperties( Properties properties )
    {
        Map<String, String> h = new ConcurrentHashMap<String, String>( properties.size() );
        for ( Enumeration e = properties.keys() ; e.hasMoreElements() ; )
        {
            Object k = e.nextElement();
            Object v = properties.get( k );
            if ( k.getClass() == String.class && v.getClass() == String.class )
            {
                h.put( (String) k, (String) v );
            }
        }
        return h;
    }

    public String getStatisticsFileName( String configurationHash )
    {
        return getReportsDirectory().getParentFile().getParentFile() + File.separator + ".surefire-"
            + configurationHash;
    }

    StartupConfiguration createStartupConfiguration( ProviderInfo provider,
                                                     ClassLoaderConfiguration classLoaderConfiguration )
        throws MojoExecutionException, MojoFailureException
    {
        try
        {
            // cache the provider lookup
            String providerName = provider.getProviderName();
            Classpath providerClasspath = ClasspathCache.getCachedClassPath( providerName );
            if ( providerClasspath == null )
            {
                providerClasspath = provider.getProviderClasspath();
                ClasspathCache.setCachedClasspath( providerName, providerClasspath );
            }
            Artifact surefireArtifact = getCommonArtifact();
            Classpath inprocClassPath = providerClasspath.
                    addClassPathElementUrl( surefireArtifact.getFile().getAbsolutePath() )
                    .addClassPathElementUrl( getApiArtifact().getFile().getAbsolutePath() );

            final Classpath testClasspath = generateTestClasspath();

            getLog().debug( testClasspath.getLogMessage( "test" ) );
            getLog().debug( providerClasspath.getLogMessage( "provider" ) );

            getLog().debug( testClasspath.getCompactLogMessage( "test(compact)" ) );
            getLog().debug( providerClasspath.getCompactLogMessage( "provider(compact)" ) );

            final ClasspathConfiguration classpathConfiguration =
                new ClasspathConfiguration( testClasspath, providerClasspath, inprocClassPath,
                                            effectiveIsEnableAssertions(), isChildDelegation() );

            return new StartupConfiguration( providerName, classpathConfiguration, classLoaderConfiguration,
                                             isForking(), false );
        }
        catch ( ArtifactResolutionException e )
        {
            throw new MojoExecutionException( "Unable to generate classpath: " + e, e );
        }
        catch ( ArtifactNotFoundException e )
        {
            throw new MojoExecutionException( "Unable to generate classpath: " + e, e );
        }
        catch ( InvalidVersionSpecificationException e )
        {
            throw new MojoExecutionException( "Unable to generate classpath: " + e, e );
        }
    }

    private Artifact getCommonArtifact()
    {
        return getPluginArtifactMap().get( "org.apache.maven.surefire:maven-surefire-common" );
    }

    private Artifact getApiArtifact()
    {
        return getPluginArtifactMap().get( "org.apache.maven.surefire:surefire-api" );
    }

    private StartupReportConfiguration getStartupReportConfiguration( String configChecksum )
    {
        return new StartupReportConfiguration( isUseFile(), isPrintSummary(), getReportFormat(),
                                               isRedirectTestOutputToFile(), isDisableXmlReport(),
                                               getReportsDirectory(), isTrimStackTrace(), getReportNameSuffix(),
                                               configChecksum, requiresRunHistory(), getRerunFailingTestsCount() );
    }

    private boolean isSpecificTestSpecified()
    {
        return StringUtils.isNotBlank( getTest() );
    }

    private boolean isValidSuiteXmlFileConfig()
    {
        return getSuiteXmlFiles() != null && getSuiteXmlFiles().length != 0;
    }

    @SuppressWarnings( "checkstyle:modifierorder" )
    private @Nonnull List<String> readListFromFile( @Nonnull final File file )
    {
        getLog().debug( "Reading list from: " + file );

        if ( !file.exists() )
        {
            throw new RuntimeException( "Failed to load list from file: " + file );
        }

        try
        {
            List<String> list = FileUtils.loadFile( file );

            if ( getLog().isDebugEnabled() )
            {
                getLog().debug( "List contents:" );
                for ( String entry : list )
                {
                    getLog().debug( "  " + entry );
                }
            }
            return list;
        }
        catch ( IOException e )
        {
            throw new RuntimeException( "Failed to load list from file: " + file, e );
        }
    }

    private void maybeAppendList( List<String> base, List<String> list )
    {
        if ( list != null )
        {
            base.addAll( list );
        }
    }

    @SuppressWarnings( "checkstyle:modifierorder" )
    private @Nonnull List<String> getExcludeList()
        throws MojoFailureException
    {
        List<String> actualExcludes = null;
        if ( isSpecificTestSpecified() )
        {
            // Check to see if we are running a single test. The raw parameter will
            // come through if it has not been set.
            // FooTest -> **/FooTest.java

            actualExcludes = new ArrayList<String>();
        }
        else
        {
            if ( getExcludesFile() != null )
            {
                actualExcludes = readListFromFile( getExcludesFile() );
            }

            // If we have excludesFile, and we have excludes, then append excludes to excludesFile content
            if ( actualExcludes == null )
            {
                actualExcludes = getExcludes();
            }
            else
            {
                maybeAppendList( actualExcludes, getExcludes() );
            }

            checkMethodFilterInIncludesExcludes( actualExcludes );

            // defaults here, qdox doesn't like the end javadoc value
            // Have to wrap in an ArrayList as surefire expects an ArrayList instead of a List for some reason
            if ( actualExcludes == null || actualExcludes.isEmpty() )
            {
                actualExcludes = Collections.singletonList( "**/*$*" );
            }
        }
        return filterNulls( actualExcludes );
    }

    private List<String> getIncludeList()
        throws MojoFailureException
    {
        List<String> includes = null;
        if ( isSpecificTestSpecified() )
        {
            includes = Collections.singletonList( getTest() );
        }
        else
        {
            if ( getIncludesFile() != null )
            {
                includes = readListFromFile( getIncludesFile() );
            }

            // If we have includesFile, and we have includes, then append includes to includesFile content
            if ( includes == null )
            {
                includes = getIncludes();
            }
            else
            {
                maybeAppendList( includes, getIncludes() );
            }

            checkMethodFilterInIncludesExcludes( includes );

            // defaults here, qdox doesn't like the end javadoc value
            // Have to wrap in an ArrayList as surefire expects an ArrayList instead of a List for some reason
            if ( includes == null || includes.isEmpty() )
            {
                includes = Arrays.asList( getDefaultIncludes() );
            }
        }

        return filterNulls( includes );
    }

    private void checkMethodFilterInIncludesExcludes( Iterable<String> patterns )
        throws MojoFailureException
    {
        if ( patterns != null )
        {
            for ( String pattern : patterns )
            {
                if ( pattern != null && pattern.contains( "#" ) )
                {
                    throw new MojoFailureException( "Method filter prohibited in "
                                                        + "includes|excludes|includesFile|excludesFile parameter: "
                                                        + pattern );
                }
            }
        }
    }

    private TestListResolver getIncludedAndExcludedTests()
        throws MojoFailureException
    {
        if ( includedExcludedTests == null )
        {
            includedExcludedTests = new TestListResolver( getIncludeList(), getExcludeList() );
        }
        return includedExcludedTests;
    }

    public TestListResolver getSpecificTests()
    {
        if ( specificTests == null )
        {
            specificTests = new TestListResolver( getTest() );
        }
        return specificTests;
    }

    @SuppressWarnings( "checkstyle:modifierorder" )
    private @Nonnull List<String> filterNulls( @Nonnull List<String> toFilter )
    {
        List<String> result = new ArrayList<String>( toFilter.size() );
        for ( String item : toFilter )
        {
            if ( item != null )
            {
                result.add( item );
            }
        }

        return result;
    }

    private Artifact getTestNgArtifact()
        throws MojoExecutionException
    {
        Artifact artifact = getProjectArtifactMap().get( getTestNGArtifactName() );
        Artifact projectArtifact = project.getArtifact();
        String projectArtifactName = projectArtifact.getGroupId() + ":" + projectArtifact.getArtifactId();

        if ( artifact != null )
        {
            VersionRange range = createVersionRange();
            if ( !range.containsVersion( new DefaultArtifactVersion( artifact.getVersion() ) ) )
            {
                throw new MojoExecutionException(
                    "TestNG support requires version 4.7 or above. You have declared version "
                        + artifact.getVersion() );
            }
        }
        else if ( projectArtifactName.equals( getTestNGArtifactName() ) )
        {
            artifact = projectArtifact;
        }

        return artifact;

    }

    private VersionRange createVersionRange()
    {
        try
        {
            return VersionRange.createFromVersionSpec( "[4.7,)" );
        }
        catch ( InvalidVersionSpecificationException e )
        {
            throw new RuntimeException( e );
        }
    }

    private Artifact getJunitArtifact()
    {
        Artifact artifact = getProjectArtifactMap().get( getJunitArtifactName() );
        Artifact projectArtifact = project.getArtifact();
        String projectArtifactName = projectArtifact.getGroupId() + ":" + projectArtifact.getArtifactId();

        if ( artifact == null && projectArtifactName.equals( getJunitArtifactName() ) )
        {
            artifact = projectArtifact;
        }

        return artifact;
    }

    private Artifact getJunitDepArtifact()
    {
        return getProjectArtifactMap().get( "junit:junit-dep" );
    }

    protected ForkStarter createForkStarter( ProviderInfo provider, ForkConfiguration forkConfiguration,
                                             ClassLoaderConfiguration classLoaderConfiguration,
                                             RunOrderParameters runOrderParameters, Log log )
        throws MojoExecutionException, MojoFailureException
    {
        StartupConfiguration startupConfiguration = createStartupConfiguration( provider, classLoaderConfiguration );
        String configChecksum = getConfigChecksum();
        StartupReportConfiguration startupReportConfiguration = getStartupReportConfiguration( configChecksum );
        ProviderConfiguration providerConfiguration = createProviderConfiguration( runOrderParameters );
        return new ForkStarter( providerConfiguration, startupConfiguration, forkConfiguration,
                                getForkedProcessTimeoutInSeconds(), startupReportConfiguration, log );
    }

    protected InPluginVMSurefireStarter createInprocessStarter( ProviderInfo provider,
                                                                ClassLoaderConfiguration classLoaderConfiguration,
                                                                RunOrderParameters runOrderParameters )
        throws MojoExecutionException, MojoFailureException
    {
        StartupConfiguration startupConfiguration = createStartupConfiguration( provider, classLoaderConfiguration );
        String configChecksum = getConfigChecksum();
        StartupReportConfiguration startupReportConfiguration = getStartupReportConfiguration( configChecksum );
        ProviderConfiguration providerConfiguration = createProviderConfiguration( runOrderParameters );
        return new InPluginVMSurefireStarter( startupConfiguration, providerConfiguration, startupReportConfiguration );

    }

    protected ForkConfiguration getForkConfiguration()
    {
        File tmpDir = getSurefireTempDir();
        //noinspection ResultOfMethodCallIgnored
        tmpDir.mkdirs();

        Artifact shadeFire = getPluginArtifactMap().get( "org.apache.maven.surefire:surefire-shadefire" );

        final Classpath bootClasspathConfiguration =
            getArtifactClasspath( shadeFire != null ? shadeFire : surefireBooterArtifact );

        return new ForkConfiguration( bootClasspathConfiguration, tmpDir, getEffectiveDebugForkedProcess(),
                                      getEffectiveJvm(),
                                      getWorkingDirectory() != null ? getWorkingDirectory() : getBasedir(),
                                      getProject().getModel().getProperties(),
                                      getArgLine(), getEnvironmentVariables(), getLog().isDebugEnabled(),
                                      getEffectiveForkCount(), reuseForks );
    }

    private void convertDeprecatedForkMode()
    {
        String effectiveForkMode = getEffectiveForkMode();
        // FORK_ONCE (default) is represented by the default values of forkCount and reuseForks
        if ( ForkConfiguration.FORK_PERTHREAD.equals( effectiveForkMode ) )
        {
            forkCount = String.valueOf( threadCount );
        }
        else if ( ForkConfiguration.FORK_NEVER.equals( effectiveForkMode ) )
        {
            forkCount = "0";
        }
        else if ( ForkConfiguration.FORK_ALWAYS.equals( effectiveForkMode ) )
        {
            forkCount = "1";
            reuseForks = false;
        }

        if ( !ForkConfiguration.FORK_ONCE.equals( getForkMode() ) )
        {
            getLog().warn(
                "The parameter forkMode is deprecated since version 2.14. Use forkCount and reuseForks instead." );
        }
    }

    @SuppressWarnings( "checkstyle:emptyblock" )
    protected int getEffectiveForkCount()
    {
        if ( effectiveForkCount < 0 )
        {
            try
            {
                effectiveForkCount = convertWithCoreCount( forkCount );
            }
            catch ( NumberFormatException ignored )
            {
            }

            if ( effectiveForkCount < 0 )
            {
                throw new IllegalArgumentException( "Fork count " + forkCount.trim() + " is not a legal value." );
            }
        }

        return effectiveForkCount;
    }

    protected int convertWithCoreCount( String count )
    {
        String trimmed = count.trim();
        if ( trimmed.endsWith( "C" ) )
        {
            double multiplier = Double.parseDouble( trimmed.substring( 0, trimmed.length() - 1 ) );
            double calculated = multiplier * ( (double) Runtime.getRuntime().availableProcessors() );
            return calculated > 0d ? Math.max( (int) calculated, 1 ) : 0;
        }
        else
        {
            return Integer.parseInt( trimmed );
        }
    }

    private String getEffectiveDebugForkedProcess()
    {
        String debugForkedProcess = getDebugForkedProcess();
        if ( "true".equals( debugForkedProcess ) )
        {
            return "-Xdebug -Xnoagent -Djava.compiler=NONE"
                + " -Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005";
        }
        return debugForkedProcess;
    }

    private String getEffectiveJvm()
    {
        String jvmToUse = getJvm();
        if ( toolchain != null && jvmToUse == null )
        {
            jvmToUse = toolchain.findTool( "java" ); //NOI18N
        }

        if ( StringUtils.isEmpty( jvmToUse ) )
        {
            // use the same JVM as the one used to run Maven (the "java.home" one)
            jvmToUse = System.getProperty( "java.home" ) + File.separator + "bin" + File.separator + "java";
            getLog().debug( "Using JVM: " + jvmToUse );
        }

        return jvmToUse;
    }


    private Artifact getSurefireBooterArtifact()
    {
        Artifact artifact = getPluginArtifactMap().get( "org.apache.maven.surefire:surefire-booter" );
        if ( artifact == null )
        {
            throw new RuntimeException( "Unable to locate surefire-booter in the list of plugin artifacts" );
        }
        artifact.isSnapshot(); // MNG-2961: before Maven 2.0.8, fixes getBaseVersion to be -SNAPSHOT if needed
        return artifact;
    }


    /**
     * Where surefire stores its own temp files
     *
     * @return A file pointing to the location of surefire's own temp files
     */
    private File getSurefireTempDir()
    {
        return new File( getReportsDirectory().getParentFile(), "surefire" );
    }

    /**
     * Operates on raw plugin paramenters, not the "effective" values.
     *
     * @return The checksum
     */
    private String getConfigChecksum()
    {
        ChecksumCalculator checksum = new ChecksumCalculator();
        checksum.add( getPluginName() );
        checksum.add( isSkipTests() );
        checksum.add( isSkipExec() );
        checksum.add( isSkip() );
        checksum.add( getTestClassesDirectory() );
        checksum.add( getClassesDirectory() );
        checksum.add( getClasspathDependencyExcludes() );
        checksum.add( getClasspathDependencyScopeExclude() );
        checksum.add( getAdditionalClasspathElements() );
        checksum.add( getReportsDirectory() );
        checksum.add( getTestSourceDirectory() );
        checksum.add( getTest() );
        checksum.add( getIncludes() );
        checksum.add( getSkipAfterFailureCount() );
        checksum.add( getShutdown() );
        checksum.add( getExcludes() );
        checksum.add( getLocalRepository() );
        checksum.add( getSystemProperties() );
        checksum.add( getSystemPropertyVariables() );
        checksum.add( getSystemPropertiesFile() );
        checksum.add( getProperties() );
        checksum.add( isPrintSummary() );
        checksum.add( getReportFormat() );
        checksum.add( getReportNameSuffix() );
        checksum.add( isUseFile() );
        checksum.add( isRedirectTestOutputToFile() );
        checksum.add( getForkMode() );
        checksum.add( getForkCount() );
        checksum.add( isReuseForks() );
        checksum.add( getJvm() );
        checksum.add( getArgLine() );
        checksum.add( getDebugForkedProcess() );
        checksum.add( getForkedProcessTimeoutInSeconds() );
        checksum.add( getParallelTestsTimeoutInSeconds() );
        checksum.add( getParallelTestsTimeoutForcedInSeconds() );
        checksum.add( getEnvironmentVariables() );
        checksum.add( getWorkingDirectory() );
        checksum.add( isChildDelegation() );
        checksum.add( getGroups() );
        checksum.add( getExcludedGroups() );
        checksum.add( getSuiteXmlFiles() );
        checksum.add( getJunitArtifact() );
        checksum.add( getTestNGArtifactName() );
        checksum.add( getThreadCount() );
        checksum.add( getThreadCountSuites() );
        checksum.add( getThreadCountClasses() );
        checksum.add( getThreadCountMethods() );
        checksum.add( getPerCoreThreadCount() );
        checksum.add( getUseUnlimitedThreads() );
        checksum.add( getParallel() );
        checksum.add( isParallelOptimized() );
        checksum.add( isTrimStackTrace() );
        checksum.add( getRemoteRepositories() );
        checksum.add( isDisableXmlReport() );
        checksum.add( isUseSystemClassLoader() );
        checksum.add( isUseManifestOnlyJar() );
        checksum.add( isEnableAssertions() );
        checksum.add( getObjectFactory() );
        checksum.add( getFailIfNoTests() );
        checksum.add( getRunOrder() );
        checksum.add( getDependenciesToScan() );
        addPluginSpecificChecksumItems( checksum );
        return checksum.getSha1();

    }

    protected void addPluginSpecificChecksumItems( ChecksumCalculator checksum )
    {

    }

    protected boolean hasExecutedBefore()
    {
        // A tribute to Linus Torvalds
        String configChecksum = getConfigChecksum();
        @SuppressWarnings( "unchecked" ) Map<String, String> pluginContext = getPluginContext();
        if ( pluginContext.containsKey( configChecksum ) )
        {
            getLog().info( "Skipping execution of surefire because it has already been run for this configuration" );
            return true;
        }
        pluginContext.put( configChecksum, configChecksum );

        return false;
    }

    protected ClassLoaderConfiguration getClassLoaderConfiguration()
    {
        return isForking()
            ? new ClassLoaderConfiguration( isUseSystemClassLoader(), isUseManifestOnlyJar() )
            : new ClassLoaderConfiguration( false, false );
    }

    /**
     * Generate the test classpath.
     *
     * @return List containing the classpath elements
     * @throws InvalidVersionSpecificationException
     *                                     when it happens
     * @throws MojoFailureException        when it happens
     * @throws ArtifactNotFoundException   when it happens
     * @throws ArtifactResolutionException when it happens
     */
    private Classpath generateTestClasspath()
        throws InvalidVersionSpecificationException, MojoFailureException, ArtifactResolutionException,
        ArtifactNotFoundException, MojoExecutionException
    {
        List<String> classpath = new ArrayList<String>( 2 + getProject().getArtifacts().size() );

        classpath.add( getTestClassesDirectory().getAbsolutePath() );

        classpath.add( getClassesDirectory().getAbsolutePath() );

        @SuppressWarnings( "unchecked" ) Set<Artifact> classpathArtifacts = getProject().getArtifacts();

        if ( getClasspathDependencyScopeExclude() != null && !getClasspathDependencyScopeExclude().equals( "" ) )
        {
            ArtifactFilter dependencyFilter = new ScopeArtifactFilter( getClasspathDependencyScopeExclude() );
            classpathArtifacts = this.filterArtifacts( classpathArtifacts, dependencyFilter );
        }

        if ( getClasspathDependencyExcludes() != null )
        {
            ArtifactFilter dependencyFilter =
                new PatternIncludesArtifactFilter( Arrays.asList( getClasspathDependencyExcludes() ) );
            classpathArtifacts = this.filterArtifacts( classpathArtifacts, dependencyFilter );
        }

        for ( Artifact artifact : classpathArtifacts )
        {
            if ( artifact.getArtifactHandler().isAddedToClasspath() )
            {
                File file = artifact.getFile();
                if ( file != null )
                {
                    classpath.add( file.getPath() );
                }
            }
        }

        // Add additional configured elements to the classpath
        if ( getAdditionalClasspathElements() != null )
        {
            for ( String classpathElement : getAdditionalClasspathElements() )
            {
                if ( classpathElement != null )
                {
                    Collections.addAll( classpath, StringUtils.split( classpathElement, "," ) );
                }
            }
        }

        // adding TestNG MethodSelector to the classpath
        // Todo: move
        if ( getTestNgArtifact() != null )
        {
            addTestNgUtilsArtifacts( classpath );
        }

        return new Classpath( classpath );
    }

    private void addTestNgUtilsArtifacts( List<String> classpath )
        throws ArtifactResolutionException, ArtifactNotFoundException
    {
        Artifact surefireArtifact = getPluginArtifactMap().get( "org.apache.maven.surefire:surefire-booter" );
        String surefireVersion = surefireArtifact.getBaseVersion();

        Artifact[] extraTestNgArtifacts = {
            getArtifactFactory().createArtifact( "org.apache.maven.surefire", "surefire-testng-utils", surefireVersion,
                                                 "runtime", "jar" ),

            getArtifactFactory().createArtifact( "org.apache.maven.surefire", "surefire-grouper", surefireVersion,
                                                 "runtime", "jar" )
        };

        for ( Artifact artifact : extraTestNgArtifacts )
        {
            getArtifactResolver().resolve( artifact, getRemoteRepositories(), getLocalRepository() );

            String path = artifact.getFile().getPath();
            classpath.add( path );
        }
    }

    /**
     * Return a new set containing only the artifacts accepted by the given filter.
     *
     * @param artifacts The unfiltered artifacts
     * @param filter    The filter to apply
     * @return The filtered result
     */
    private Set<Artifact> filterArtifacts( Set<Artifact> artifacts, ArtifactFilter filter )
    {
        Set<Artifact> filteredArtifacts = new LinkedHashSet<Artifact>();

        for ( Artifact artifact : artifacts )
        {
            if ( !filter.include( artifact ) )
            {
                filteredArtifacts.add( artifact );
            }
        }

        return filteredArtifacts;
    }

    private void showMap( Map<?, ?> map, String setting )
    {
        for ( Object o : map.keySet() )
        {
            String key = (String) o;
            String value = (String) map.get( key );
            getLog().debug( "Setting " + setting + " [" + key + "]=[" + value + "]" );
        }
    }


    private ArtifactResolutionResult resolveArtifact( Artifact filteredArtifact, Artifact providerArtifact )
    {
        ArtifactFilter filter = null;
        if ( filteredArtifact != null )
        {
            filter = new ExcludesArtifactFilter(
                Collections.singletonList( filteredArtifact.getGroupId() + ":" + filteredArtifact.getArtifactId() ) );
        }

        Artifact originatingArtifact = getArtifactFactory().createBuildArtifact( "dummy", "dummy", "1.0", "jar" );

        try
        {
            return getArtifactResolver().resolveTransitively( Collections.singleton( providerArtifact ),
                                                              originatingArtifact, getLocalRepository(),
                                                              getRemoteRepositories(), getMetadataSource(), filter );
        }
        catch ( ArtifactResolutionException e )
        {
            throw new RuntimeException( e );
        }
        catch ( ArtifactNotFoundException e )
        {
            throw new RuntimeException( e );
        }
    }

    private Classpath getArtifactClasspath( Artifact surefireArtifact )
    {
        Classpath existing = ClasspathCache.getCachedClassPath( surefireArtifact.getArtifactId() );
        if ( existing == null )
        {
            ArtifactResolutionResult result = resolveArtifact( null, surefireArtifact );

            List<String> items = new ArrayList<String>();
            for ( Object o : result.getArtifacts() )
            {
                Artifact artifact = (Artifact) o;

                getLog().debug(
                    "Adding to " + getPluginName() + " booter test classpath: " + artifact.getFile().getAbsolutePath()
                    + " Scope: " + artifact.getScope() );

                items.add( artifact.getFile().getAbsolutePath() );
            }
            existing = new Classpath( items );
            ClasspathCache.setCachedClasspath( surefireArtifact.getArtifactId(), existing );
        }
        return existing;
    }

    private Properties getUserProperties()
    {
        Properties props = null;
        try
        {
            // try calling MavenSession.getUserProperties() from Maven 2.1.0-M1+
            Method getUserProperties = getSession().getClass().getMethod( "getUserProperties" );
            props = (Properties) getUserProperties.invoke( getSession() );
        }
        catch ( Exception e )
        {
            String msg = "Build uses Maven 2.0.x, cannot propagate system properties"
                + " from command line to tests (cf. SUREFIRE-121)";
            if ( getLog().isDebugEnabled() )
            {
                getLog().warn( msg, e );
            }
            else
            {
                getLog().warn( msg );
            }
        }
        if ( props == null )
        {
            props = new Properties();
        }
        return props;
    }


    private void ensureWorkingDirectoryExists()
        throws MojoFailureException
    {
        if ( getWorkingDirectory() == null )
        {
            throw new MojoFailureException( "workingDirectory cannot be null" );
        }

        if ( isForking() )
        {
            // Postpone directory creation till forked JVM creation
            // see ForkConfiguration.createCommandLine
            return;
        }

        if ( !getWorkingDirectory().exists() )
        {
            if ( !getWorkingDirectory().mkdirs() )
            {
                throw new MojoFailureException( "Cannot create workingDirectory " + getWorkingDirectory() );
            }
        }

        if ( !getWorkingDirectory().isDirectory() )
        {
            throw new MojoFailureException(
                "workingDirectory " + getWorkingDirectory() + " exists and is not a directory" );
        }
    }

    private void ensureParallelRunningCompatibility()
        throws MojoFailureException
    {
        if ( isMavenParallel() && isNotForking() )
        {
            throw new MojoFailureException( "parallel maven execution is not compatible with surefire forkCount 0" );
        }
    }

    private void ensureThreadCountWithPerThread()
        throws MojoFailureException
    {
        if ( ForkConfiguration.FORK_PERTHREAD.equals( getEffectiveForkMode() ) && getThreadCount() < 1 )
        {
            throw new MojoFailureException( "Fork mode perthread requires a thread count" );
        }
    }

    private void warnIfUselessUseSystemClassLoaderParameter()
    {
        if ( isUseSystemClassLoader() && isNotForking() )
        {
            getLog().warn( "useSystemClassloader setting has no effect when not forking" );
        }
    }

    private boolean isNotForking()
    {
        return !isForking();
    }

    private List<CommandLineOption> commandLineOptions()
    {
        return SurefireHelper.commandLineOptions( getSession(), getLog() );
    }

    private void warnIfDefunctGroupsCombinations()
        throws MojoFailureException, MojoExecutionException
    {
        if ( isAnyGroupsSelected() )
        {
            if ( getTestNgArtifact() == null )
            {
                Artifact junitArtifact = getJunitArtifact();
                boolean junit47Compatible = isJunit47Compatible( junitArtifact );
                if ( !junit47Compatible )
                {
                    if ( junitArtifact != null )
                    {
                        throw new MojoFailureException( "groups/excludedGroups are specified but JUnit version on "
                                                            + "classpath is too old to support groups. "
                                                            + "Check your dependency:tree to see if your project "
                                                            + "is picking up an old junit version" );
                    }
                    throw new MojoFailureException( "groups/excludedGroups require TestNG or JUnit48+ on project test "
                                                        + "classpath" );
                }
            }

        }
    }

    private void warnIfRerunClashes()
        throws MojoFailureException
    {
        if ( getRerunFailingTestsCount() < 0 )
        {
            throw new MojoFailureException( "Parameter \"rerunFailingTestsCount\" should not be negative." );
        }

        if ( getSkipAfterFailureCount() < 0 )
        {
            throw new MojoFailureException( "Parameter \"skipAfterFailureCount\" should not be negative." );
        }

        boolean isRerun = getRerunFailingTestsCount() > 0;
        boolean isFailFast = getSkipAfterFailureCount() > 0;
        if ( isRerun && isFailFast )
        {
            throw new MojoFailureException( "Parameters [\"rerunFailingTestsCount\", \"skipAfterFailureCount\"] "
                                                + "should not be enabled together." );
        }
    }

    private void warnIfWrongShutdownValue()
        throws MojoFailureException
    {
        if ( !Shutdown.isKnown( getShutdown() ) )
        {
            throw new MojoFailureException( "Parameter \"shutdown\" should have values " + Shutdown.listParameters() );
        }
    }

    private void warnIfNotApplicableSkipAfterFailureCount()
        throws MojoFailureException
    {
        int skipAfterFailureCount = getSkipAfterFailureCount();

        if ( skipAfterFailureCount < 0 )
        {
            throw new MojoFailureException( "Parameter \"skipAfterFailureCount\" should not be negative." );
        }
        else if ( skipAfterFailureCount > 0 )
        {
            try
            {
                Artifact testng = getTestNgArtifact();
                if ( testng != null )
                {
                    VersionRange range = VersionRange.createFromVersionSpec( "[5.10,)" );
                    if ( !range.containsVersion( new DefaultArtifactVersion( testng.getVersion() ) ) )
                    {
                        throw new MojoFailureException(
                            "Parameter \"skipAfterFailureCount\" expects TestNG Version 5.10 or higher. "
                                + "java.lang.NoClassDefFoundError: org/testng/IInvokedMethodListener" );
                    }
                }
                else
                {
                    // TestNG is dependent on JUnit
                    Artifact junit = getJunitArtifact();
                    if ( junit != null )
                    {
                        VersionRange range = VersionRange.createFromVersionSpec( "[4.0,)" );
                        if ( !range.containsVersion( new DefaultArtifactVersion( junit.getVersion() ) ) )
                        {
                            throw new MojoFailureException(
                                "Parameter \"skipAfterFailureCount\" expects JUnit Version 4.0 or higher. "
                                    + "java.lang.NoSuchMethodError: "
                                    + "org.junit.runner.notification.RunNotifier.pleaseStop()V" );
                        }
                    }
                }
            }
            catch ( MojoExecutionException e )
            {
                throw new MojoFailureException( e.getLocalizedMessage() );
            }
            catch ( InvalidVersionSpecificationException e )
            {
                throw new RuntimeException( e );
            }
        }
    }

    final class TestNgProviderInfo
        implements ProviderInfo
    {
        private final Artifact testNgArtifact;

        TestNgProviderInfo( Artifact testNgArtifact )
        {
            this.testNgArtifact = testNgArtifact;
        }

        @SuppressWarnings( "checkstyle:modifierorder" )
        public @Nonnull String getProviderName()
        {
            return "org.apache.maven.surefire.testng.TestNGProvider";
        }

        public boolean isApplicable()
        {
            return testNgArtifact != null;
        }

        public void addProviderProperties() throws MojoExecutionException
        {
            convertTestNGParameters();
        }

        public Classpath getProviderClasspath()
            throws ArtifactResolutionException, ArtifactNotFoundException
        {
            Artifact surefireArtifact = getPluginArtifactMap().get( "org.apache.maven.surefire:surefire-booter" );
            return dependencyResolver.getProviderClasspath( "surefire-testng", surefireArtifact.getBaseVersion(),
                                                            testNgArtifact );
        }
    }

    final class JUnit3ProviderInfo
        implements ProviderInfo
    {
        @Nonnull public String getProviderName()
        {
            return "org.apache.maven.surefire.junit.JUnit3Provider";
        }

        public boolean isApplicable()
        {
            return true;
        }

        public void addProviderProperties() throws MojoExecutionException
        {
        }

        public Classpath getProviderClasspath()
            throws ArtifactResolutionException, ArtifactNotFoundException
        {
            // add the JUnit provider as default - it doesn't require JUnit to be present,
            // since it supports POJO tests.
            return dependencyResolver.getProviderClasspath( "surefire-junit3", surefireBooterArtifact.getBaseVersion(),
                                                            null );

        }
    }

    final class JUnit4ProviderInfo
        implements ProviderInfo
    {
        private final Artifact junitArtifact;

        private final Artifact junitDepArtifact;

        JUnit4ProviderInfo( Artifact junitArtifact, Artifact junitDepArtifact )
        {
            this.junitArtifact = junitArtifact;
            this.junitDepArtifact = junitDepArtifact;
        }

        @Nonnull public String getProviderName()
        {
            return "org.apache.maven.surefire.junit4.JUnit4Provider";
        }

        public boolean isApplicable()
        {
            return junitDepArtifact != null || isAnyJunit4( junitArtifact );
        }

        public void addProviderProperties() throws MojoExecutionException
        {
        }

        public Classpath getProviderClasspath()
            throws ArtifactResolutionException, ArtifactNotFoundException
        {
            return dependencyResolver.getProviderClasspath( "surefire-junit4", surefireBooterArtifact.getBaseVersion(),
                                                            null );
        }

    }

    final class JUnitCoreProviderInfo
        implements ProviderInfo
    {
        private final Artifact junitArtifact;

        private final Artifact junitDepArtifact;

        JUnitCoreProviderInfo( Artifact junitArtifact, Artifact junitDepArtifact )
        {
            this.junitArtifact = junitArtifact;
            this.junitDepArtifact = junitDepArtifact;
        }

        @Nonnull public String getProviderName()
        {
            return "org.apache.maven.surefire.junitcore.JUnitCoreProvider";
        }

        private boolean is47CompatibleJunitDep()
        {
            return junitDepArtifact != null && isJunit47Compatible( junitDepArtifact );
        }

        public boolean isApplicable()
        {
            final boolean isJunitArtifact47 = isAnyJunit4( junitArtifact ) && isJunit47Compatible( junitArtifact );
            final boolean isAny47ProvidersForcers = isAnyConcurrencySelected() || isAnyGroupsSelected();
            return isAny47ProvidersForcers && ( isJunitArtifact47 || is47CompatibleJunitDep() );
        }

        public void addProviderProperties() throws MojoExecutionException
        {
            convertJunitCoreParameters();
            convertGroupParameters();
        }

        public Classpath getProviderClasspath()
            throws ArtifactResolutionException, ArtifactNotFoundException
        {
            return dependencyResolver.getProviderClasspath( "surefire-junit47", surefireBooterArtifact.getBaseVersion(),
                                                            null );
        }
    }

    /**
     * Provides the Provider information for manually configured providers.
     */
    final class DynamicProviderInfo
        implements ConfigurableProviderInfo
    {
        final String providerName;

        DynamicProviderInfo( String providerName )
        {
            this.providerName = providerName;
        }

        public ProviderInfo instantiate( String providerName )
        {
            return new DynamicProviderInfo( providerName );
        }

        @Nonnull
        public String getProviderName()
        {
            return providerName;
        }

        public boolean isApplicable()
        {
            return true;
        }

        public void addProviderProperties() throws MojoExecutionException
        {
            // Ok this is a bit lazy.
            convertJunitCoreParameters();
            convertTestNGParameters();
        }

        public Classpath getProviderClasspath()
            throws ArtifactResolutionException, ArtifactNotFoundException
        {
            final Map<String, Artifact> pluginArtifactMap = getPluginArtifactMap();
            Artifact plugin = pluginArtifactMap.get( "org.apache.maven.plugins:maven-surefire-plugin" );
            return dependencyResolver.addProviderToClasspath( pluginArtifactMap, plugin );
        }
    }

    /**
     * @author Kristian Rosenvold
     */
    final class ProviderList
    {
        private final ProviderInfo[] wellKnownProviders;

        private final ConfigurableProviderInfo dynamicProvider;

        ProviderList( ConfigurableProviderInfo dynamicProviderInfo, ProviderInfo... wellKnownProviders )
        {
            this.wellKnownProviders = wellKnownProviders;
            this.dynamicProvider = dynamicProviderInfo;
        }

        @SuppressWarnings( "checkstyle:modifierorder" )
        @Nonnull List<ProviderInfo> resolve()
        {
            List<ProviderInfo> providersToRun = new ArrayList<ProviderInfo>();
            Set<String> manuallyConfiguredProviders = getManuallyConfiguredProviders();
            for ( String name : manuallyConfiguredProviders )
            {
                ProviderInfo wellKnown = findByName( name );
                ProviderInfo providerToAdd = wellKnown != null ? wellKnown : dynamicProvider.instantiate( name );
                logDebugOrCliShowErrors( "Using configured provider " + providerToAdd.getProviderName() );
                providersToRun.add( providerToAdd );
            }
            return manuallyConfiguredProviders.isEmpty() ? autoDetectOneProvider() : providersToRun;
        }

        @SuppressWarnings( "checkstyle:modifierorder" )
        private @Nonnull List<ProviderInfo> autoDetectOneProvider()
        {
            List<ProviderInfo> providersToRun = new ArrayList<ProviderInfo>();
            for ( ProviderInfo wellKnownProvider : wellKnownProviders )
            {
                if ( wellKnownProvider.isApplicable() )
                {
                    providersToRun.add( wellKnownProvider );
                    return providersToRun;
                }
            }
            return providersToRun;
        }

        private Set<String> getManuallyConfiguredProviders()
        {
            try
            {
                return ProviderDetector.getServiceNames( SurefireProvider.class,
                                                         Thread.currentThread().getContextClassLoader() );
            }
            catch ( IOException e )
            {
                throw new RuntimeException( e );
            }
        }

        private ProviderInfo findByName( String providerClassName )
        {
            for ( ProviderInfo wellKnownProvider : wellKnownProviders )
            {
                if ( wellKnownProvider.getProviderName().equals( providerClassName ) )
                {
                    return wellKnownProvider;
                }
            }
            return null;
        }
    }

    public List<String> getExcludes()
    {
        return excludes;
    }

    public void setExcludes( List<String> excludes )
    {
        this.excludes = excludes;
    }

    public ArtifactRepository getLocalRepository()
    {
        return localRepository;
    }

    public void setLocalRepository( ArtifactRepository localRepository )
    {
        this.localRepository = localRepository;
    }

    /**
     * @noinspection deprecation
     */
    public Properties getSystemProperties()
    {
        return systemProperties;
    }

    @SuppressWarnings( { "UnusedDeclaration", "deprecation" } )
    public void setSystemProperties( Properties systemProperties )
    {
        this.systemProperties = systemProperties;
    }

    public Map<String, String> getSystemPropertyVariables()
    {
        return systemPropertyVariables;
    }

    @SuppressWarnings( "UnusedDeclaration" )
    public void setSystemPropertyVariables( Map<String, String> systemPropertyVariables )
    {
        this.systemPropertyVariables = systemPropertyVariables;
    }

    public File getSystemPropertiesFile()
    {
        return systemPropertiesFile;
    }

    @SuppressWarnings( "UnusedDeclaration" )
    public void setSystemPropertiesFile( File systemPropertiesFile )
    {
        this.systemPropertiesFile = systemPropertiesFile;
    }

    private Properties getProperties()
    {
        return properties;
    }

    public void setProperties( Properties properties )
    {
        this.properties = properties;
    }

    public Map<String, Artifact> getPluginArtifactMap()
    {
        return pluginArtifactMap;
    }

    @SuppressWarnings( "UnusedDeclaration" )
    public void setPluginArtifactMap( Map<String, Artifact> pluginArtifactMap )
    {
        this.pluginArtifactMap = pluginArtifactMap;
    }

    public Map<String, Artifact> getProjectArtifactMap()
    {
        return projectArtifactMap;
    }

    @SuppressWarnings( "UnusedDeclaration" )
    public void setProjectArtifactMap( Map<String, Artifact> projectArtifactMap )
    {
        this.projectArtifactMap = projectArtifactMap;
    }


    public String getReportNameSuffix()
    {
        return reportNameSuffix;
    }

    @SuppressWarnings( "UnusedDeclaration" )
    public void setReportNameSuffix( String reportNameSuffix )
    {
        this.reportNameSuffix = reportNameSuffix;
    }


    public boolean isRedirectTestOutputToFile()
    {
        return redirectTestOutputToFile;
    }

    @SuppressWarnings( "UnusedDeclaration" )
    public void setRedirectTestOutputToFile( boolean redirectTestOutputToFile )
    {
        this.redirectTestOutputToFile = redirectTestOutputToFile;
    }


    public Boolean getFailIfNoTests()
    {
        return failIfNoTests;
    }

    public void setFailIfNoTests( boolean failIfNoTests )
    {
        this.failIfNoTests = failIfNoTests;
    }

    public String getForkMode()
    {
        return forkMode;
    }

    @SuppressWarnings( "UnusedDeclaration" )
    public void setForkMode( String forkMode )
    {
        this.forkMode = forkMode;
    }

    public String getJvm()
    {
        return jvm;
    }

    public String getArgLine()
    {
        return argLine;
    }

    @SuppressWarnings( "UnusedDeclaration" )
    public void setArgLine( String argLine )
    {
        this.argLine = argLine;
    }


    public Map<String, String> getEnvironmentVariables()
    {
        return environmentVariables;
    }

    @SuppressWarnings( "UnusedDeclaration" )
    public void setEnvironmentVariables( Map<String, String> environmentVariables )
    {
        this.environmentVariables = environmentVariables;
    }

    public File getWorkingDirectory()
    {
        return workingDirectory;
    }

    @SuppressWarnings( "UnusedDeclaration" )
    public void setWorkingDirectory( File workingDirectory )
    {
        this.workingDirectory = workingDirectory;
    }

    public boolean isChildDelegation()
    {
        return childDelegation;
    }

    @SuppressWarnings( "UnusedDeclaration" )
    public void setChildDelegation( boolean childDelegation )
    {
        this.childDelegation = childDelegation;
    }

    public String getGroups()
    {
        return groups;
    }

    @SuppressWarnings( "UnusedDeclaration" )
    public void setGroups( String groups )
    {
        this.groups = groups;
    }

    public String getExcludedGroups()
    {
        return excludedGroups;
    }

    @SuppressWarnings( "UnusedDeclaration" )
    public void setExcludedGroups( String excludedGroups )
    {
        this.excludedGroups = excludedGroups;
    }

    public String getJunitArtifactName()
    {
        return junitArtifactName;
    }

    @SuppressWarnings( "UnusedDeclaration" )
    public void setJunitArtifactName( String junitArtifactName )
    {
        this.junitArtifactName = junitArtifactName;
    }

    public String getTestNGArtifactName()
    {
        return testNGArtifactName;
    }

    @SuppressWarnings( "UnusedDeclaration" )
    public void setTestNGArtifactName( String testNGArtifactName )
    {
        this.testNGArtifactName = testNGArtifactName;
    }

    public int getThreadCount()
    {
        return threadCount;
    }

    @SuppressWarnings( "UnusedDeclaration" )
    public void setThreadCount( int threadCount )
    {
        this.threadCount = threadCount;
    }

    public boolean getPerCoreThreadCount()
    {
        return perCoreThreadCount;
    }

    @SuppressWarnings( "UnusedDeclaration" )
    public void setPerCoreThreadCount( boolean perCoreThreadCount )
    {
        this.perCoreThreadCount = perCoreThreadCount;
    }

    public boolean getUseUnlimitedThreads()
    {
        return useUnlimitedThreads;
    }

    @SuppressWarnings( "UnusedDeclaration" )
    public void setUseUnlimitedThreads( boolean useUnlimitedThreads )
    {
        this.useUnlimitedThreads = useUnlimitedThreads;
    }

    public String getParallel()
    {
        return parallel;
    }

    @SuppressWarnings( "UnusedDeclaration" )
    public void setParallel( String parallel )
    {
        this.parallel = parallel;
    }

    public boolean isParallelOptimized()
    {
        return parallelOptimized;
    }

    @SuppressWarnings( "UnusedDeclaration" )
    public void setParallelOptimized( boolean parallelOptimized )
    {
        this.parallelOptimized = parallelOptimized;
    }

    public int getThreadCountSuites()
    {
        return threadCountSuites;
    }

    public void setThreadCountSuites( int threadCountSuites )
    {
        this.threadCountSuites = threadCountSuites;
    }

    public int getThreadCountClasses()
    {
        return threadCountClasses;
    }

    public void setThreadCountClasses( int threadCountClasses )
    {
        this.threadCountClasses = threadCountClasses;
    }

    public int getThreadCountMethods()
    {
        return threadCountMethods;
    }

    public void setThreadCountMethods( int threadCountMethods )
    {
        this.threadCountMethods = threadCountMethods;
    }

    public boolean isTrimStackTrace()
    {
        return trimStackTrace;
    }

    @SuppressWarnings( "UnusedDeclaration" )
    public void setTrimStackTrace( boolean trimStackTrace )
    {
        this.trimStackTrace = trimStackTrace;
    }

    public ArtifactResolver getArtifactResolver()
    {
        return artifactResolver;
    }

    @SuppressWarnings( "UnusedDeclaration" )
    public void setArtifactResolver( ArtifactResolver artifactResolver )
    {
        this.artifactResolver = artifactResolver;
    }

    public ArtifactFactory getArtifactFactory()
    {
        return artifactFactory;
    }

    @SuppressWarnings( "UnusedDeclaration" )
    public void setArtifactFactory( ArtifactFactory artifactFactory )
    {
        this.artifactFactory = artifactFactory;
    }

    public List<ArtifactRepository> getRemoteRepositories()
    {
        return remoteRepositories;
    }

    @SuppressWarnings( "UnusedDeclaration" )
    public void setRemoteRepositories( List<ArtifactRepository> remoteRepositories )
    {
        this.remoteRepositories = remoteRepositories;
    }

    public ArtifactMetadataSource getMetadataSource()
    {
        return metadataSource;
    }

    @SuppressWarnings( "UnusedDeclaration" )
    public void setMetadataSource( ArtifactMetadataSource metadataSource )
    {
        this.metadataSource = metadataSource;
    }


    public boolean isDisableXmlReport()
    {
        return disableXmlReport;
    }

    @SuppressWarnings( "UnusedDeclaration" )
    public void setDisableXmlReport( boolean disableXmlReport )
    {
        this.disableXmlReport = disableXmlReport;
    }


    public boolean isEnableAssertions()
    {
        return enableAssertions;
    }

    public boolean effectiveIsEnableAssertions()
    {
        if ( getArgLine() != null )
        {
            List<String> args = Arrays.asList( getArgLine().split( " " ) );
            if ( args.contains( "-da" ) || args.contains( "-disableassertions" ) )
            {
                return false;
            }
        }
        return isEnableAssertions();
    }

    @SuppressWarnings( "UnusedDeclaration" )
    public void setEnableAssertions( boolean enableAssertions )
    {
        this.enableAssertions = enableAssertions;
    }

    public MavenSession getSession()
    {
        return session;
    }

    @SuppressWarnings( "UnusedDeclaration" )
    public void setSession( MavenSession session )
    {
        this.session = session;
    }

    public String getObjectFactory()
    {
        return objectFactory;
    }

    @SuppressWarnings( "UnusedDeclaration" )
    public void setObjectFactory( String objectFactory )
    {
        this.objectFactory = objectFactory;
    }

    public ToolchainManager getToolchainManager()
    {
        return toolchainManager;
    }

    @SuppressWarnings( "UnusedDeclaration" )
    public void setToolchainManager( ToolchainManager toolchainManager )
    {
        this.toolchainManager = toolchainManager;
    }

    public boolean isMavenParallel()
    {
        return parallelMavenExecution != null && parallelMavenExecution;
    }

    public String[] getDependenciesToScan()
    {
        return dependenciesToScan;
    }

    public void setDependenciesToScan( String[] dependenciesToScan )
    {
        this.dependenciesToScan = dependenciesToScan;
    }

    public PluginDescriptor getPluginDescriptor()
    {
        return pluginDescriptor;
    }

    public MavenProject getProject()
    {
        return project;
    }

    @SuppressWarnings( "UnusedDeclaration" )
    public void setProject( MavenProject project )
    {
        this.project = project;
    }

    public File getTestSourceDirectory()
    {
        return testSourceDirectory;
    }

    public void setTestSourceDirectory( File testSourceDirectory )
    {
        this.testSourceDirectory = testSourceDirectory;
    }

    public String getForkCount()
    {
        return forkCount;
    }

    public boolean isReuseForks()
    {
        return reuseForks;
    }

    public String[] getAdditionalClasspathElements()
    {
        return additionalClasspathElements;
    }

    public void setAdditionalClasspathElements( String[] additionalClasspathElements )
    {
        this.additionalClasspathElements = additionalClasspathElements;
    }

    public String[] getClasspathDependencyExcludes()
    {
        return classpathDependencyExcludes;
    }

    public void setClasspathDependencyExcludes( String[] classpathDependencyExcludes )
    {
        this.classpathDependencyExcludes = classpathDependencyExcludes;
    }

    public String getClasspathDependencyScopeExclude()
    {
        return classpathDependencyScopeExclude;
    }

    public void setClasspathDependencyScopeExclude( String classpathDependencyScopeExclude )
    {
        this.classpathDependencyScopeExclude = classpathDependencyScopeExclude;
    }

    protected void logDebugOrCliShowErrors( CharSequence s )
    {
        SurefireHelper.logDebugOrCliShowErrors( s, getLog(), cli );
    }
}
