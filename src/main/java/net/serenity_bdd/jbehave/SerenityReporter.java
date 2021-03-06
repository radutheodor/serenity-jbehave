package net.serenity_bdd.jbehave;

import ch.lambdaj.function.convert.Converter;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import net.serenity_bdd.core.Serenity;
import net.serenity_bdd.core.SerenityListeners;
import net.serenity_bdd.core.SerenityReports;
import net.thucydides.core.model.DataTable;
import net.thucydides.core.model.TestOutcome;
import net.thucydides.core.model.TestResult;
import net.thucydides.core.model.TestTag;
import net.thucydides.core.reports.ReportService;
import net.thucydides.core.steps.BaseStepListener;
import net.thucydides.core.steps.ExecutedStepDescription;
import net.thucydides.core.steps.StepEventBus;
import net.thucydides.core.steps.StepFailure;
import net.thucydides.core.util.Inflector;
import net.thucydides.core.util.NameConverter;
import net.thucydides.core.webdriver.Configuration;
import net.thucydides.core.webdriver.ThucydidesWebDriverSupport;
import net.thucydides.core.webdriver.WebdriverProxyFactory;
import org.codehaus.plexus.util.StringUtils;
import org.jbehave.core.configuration.Keywords;
import org.jbehave.core.model.ExamplesTable;
import org.jbehave.core.model.GivenStories;
import org.jbehave.core.model.GivenStory;
import org.jbehave.core.model.Lifecycle;
import org.jbehave.core.model.Meta;
import org.jbehave.core.model.Narrative;
import org.jbehave.core.model.OutcomesTable;
import org.jbehave.core.model.Scenario;
import org.jbehave.core.model.Story;
import org.jbehave.core.model.StoryDuration;
import org.jbehave.core.reporters.StoryReporter;
import org.junit.internal.AssumptionViolatedException;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Stack;

import static ch.lambdaj.Lambda.convert;
import static ch.lambdaj.Lambda.extract;
import static ch.lambdaj.Lambda.flatten;
import static ch.lambdaj.Lambda.on;

public class SerenityReporter implements StoryReporter {

    private ThreadLocal<SerenityListeners> serenityListenersThreadLocal;
    private ThreadLocal<ReportService> reportServiceThreadLocal;
    private final List<BaseStepListener> baseStepListeners;

    private final Configuration systemConfiguration;
    private static final String OPEN_PARAM_CHAR = "\uff5f";
    private static final String CLOSE_PARAM_CHAR = "\uff60";

    private static final String PENDING = "pending";
    private static final String SKIP = "skip";
    private static final String WIP = "wip";

    private static Optional<TestResult> forcedStoryResult;
    private static Optional<TestResult> forcedScenarioResult;

    private GivenStoryMonitor givenStoryMonitor;

    public SerenityReporter(Configuration systemConfiguration) {
        this.systemConfiguration = systemConfiguration;
        serenityListenersThreadLocal = new ThreadLocal<>();
        reportServiceThreadLocal = new ThreadLocal<>();
        baseStepListeners = Lists.newArrayList();
        givenStoryMonitor = new GivenStoryMonitor();
        clearStoryResult();
        clearScenarioResult();

    }

    private void clearStoryResult() {
        forcedStoryResult = Optional.absent();
    }

    private void clearScenarioResult() {
        forcedScenarioResult = Optional.absent();
    }

    protected void clearListeners() {
        serenityListenersThreadLocal.remove();
        reportServiceThreadLocal.remove();
        givenStoryMonitor.clear();
    }

    protected SerenityListeners getSerenityListeners() {
        if (serenityListenersThreadLocal.get() == null) {
            SerenityListeners listeners = SerenityReports.setupListeners(systemConfiguration);
            serenityListenersThreadLocal.set(listeners);
            synchronized (baseStepListeners) {
                baseStepListeners.add(listeners.getBaseStepListener());
            }
        }
        return serenityListenersThreadLocal.get();
    }

    protected ReportService getReportService() {
        return SerenityReports.getReportService(systemConfiguration);
    }

    public void storyNotAllowed(Story story, String filter) {
    }

    public void storyCancelled(Story story, StoryDuration storyDuration) {
    }

    private Stack<Story> storyStack = new Stack<Story>();

    private Stack<String> activeScenarios = new Stack<String>();
    private List<String> givenStories = Lists.newArrayList();

    private Story currentStory() {
        return storyStack.peek();
    }

    private void currentStoryIs(Story story) {
        storyStack.push(story);
    }

    private Map<String, String> storyMetadata;

    public void beforeStory(Story story, boolean givenStory) {
        clearStoryResult();
        currentStoryIs(story);
        noteAnyGivenStoriesFor(story);
        storyMetadata = getMetadataFrom(story.getMeta());
        if (!isFixture(story) && !givenStory) {

            activeScenarios.clear();

            configureDriver(story);

            SerenityStepFactory.resetContext();

            getSerenityListeners().withDriver(ThucydidesWebDriverSupport.getDriver());

            if (!isAStoryLevelGiven(story)) {
                startTestSuiteForStory(story);
                if (givenStoriesPresentFor(story)) {
                    startTestForFirstScenarioIn(story);
                }
            }
        } else if(givenStory) {
            shouldNestScenarios(true);
        }
    }

    private boolean nestScenarios = false;

    private boolean shouldNestScenarios() {
        return nestScenarios;
    }

    private void shouldNestScenarios(boolean nestScenarios) {
        this.nestScenarios = nestScenarios;
    }

    private void startTestForFirstScenarioIn(Story story) {
        Scenario firstScenario = story.getScenarios().get(0);
        startScenarioCalled(firstScenario.getTitle());
        StepEventBus.getEventBus().stepStarted(ExecutedStepDescription.withTitle("Preconditions"));
        shouldNestScenarios(true);
    }

    public void beforeScenario(String scenarioTitle) {

        clearScenarioResult();

        if (shouldRestartDriverBeforeEachScenario() && !shouldNestScenarios()) {
            WebdriverProxyFactory.resetDriver(ThucydidesWebDriverSupport.getDriver());
        }

        if (shouldResetStepsBeforeEachScenario()) {
            SerenityStepFactory.resetContext();
        }

        if(isCurrentScenario(scenarioTitle)) {
            //This is our scenario
        } else if (shouldNestScenarios()) {
            startNewStep(scenarioTitle);
        } else {
            startScenarioCalled(scenarioTitle);
        }
        if (pendingScenario()) {
            StepEventBus.getEventBus().testPending();
        } else if (skippedScenario()) {
            StepEventBus.getEventBus().testIgnored();
        }
    }

    private boolean pendingScenario() {
        return (forcedScenarioResult.or(TestResult.UNDEFINED) == TestResult.PENDING);
    }

    private boolean skippedScenario() {
        return (forcedScenarioResult.or(TestResult.UNDEFINED) == TestResult.SKIPPED);
    }

    private boolean isCurrentScenario(String scenarioTitle) {
        return !activeScenarios.empty() && scenarioTitle.equals(activeScenarios.peek());
    }

    private void startNewStep(String scenarioTitle) {
        if (givenStoryMonitor.isInGivenStory() && StepEventBus.getEventBus().areStepsRunning()) {
            StepEventBus.getEventBus().updateCurrentStepTitle(scenarioTitle);
        } else {
            StepEventBus.getEventBus().stepStarted(ExecutedStepDescription.withTitle(scenarioTitle));
        }
    }

    private boolean givenStoriesPresentFor(Story story) {
        return !story.getGivenStories().getStories().isEmpty();
    }

    private void startTestSuiteForStory(Story story) {
        String storyName = removeSuffixFrom(story.getName());
        String storyTitle = NameConverter.humanize(storyName);

        net.thucydides.core.model.Story userStory
                = net.thucydides.core.model.Story.withIdAndPath(storyName, storyTitle, story.getPath())
                .withNarrative(getNarrativeFrom(story));
        StepEventBus.getEventBus().testSuiteStarted(userStory);
        registerTags(story);
    }

    private String getNarrativeFrom(Story story) {
        return (!story.getNarrative().isEmpty()) ?
             story.getNarrative().asString(new Keywords()).trim() : "";
    }

    private void noteAnyGivenStoriesFor(Story story) {
        for (GivenStory given : story.getGivenStories().getStories()) {
            String givenStoryName = new File(given.getPath()).getName();
            givenStories.add(givenStoryName);
        }
    }

    private boolean isAStoryLevelGiven(Story story) {
        for (String givenStoryName : givenStories) {
            if (hasSameName(story, givenStoryName)) {
                return true;
            }
        }
        return false;
    }

    private void givenStoryDone(Story story) {
        givenStories.remove(story.getName());
    }

    private boolean hasSameName(Story story, String givenStoryName) {
        return story.getName().equalsIgnoreCase(givenStoryName);
    }

    private void configureDriver(Story story) {
        StepEventBus.getEventBus().setUniqueSession(systemConfiguration.getUseUniqueBrowser());
        String requestedDriver = getRequestedDriver(story.getMeta());
        if (StringUtils.isNotEmpty(requestedDriver)) {
            ThucydidesWebDriverSupport.initialize(requestedDriver);
        } else {
            ThucydidesWebDriverSupport.initialize();
        }
    }

    private void registerTags(Story story) {
        registerStoryIssues(story.getMeta());
        registerStoryFeaturesAndEpics(story.getMeta());
        registerStoryTags(story.getMeta());
        registerStoryMeta(story.getMeta());
    }

    private boolean isFixture(Story story) {
        return (story.getName().equals("BeforeStories") || story.getName().equals("AfterStories"));
    }

    private String getRequestedDriver(Meta metaData) {
        if (StringUtils.isNotEmpty(metaData.getProperty("driver"))) {
            return metaData.getProperty("driver");
        }
        if (systemConfiguration.getDriverType() != null) {
            return systemConfiguration.getDriverType().toString();
        }
        return null;
    }

    private List<String> getIssueOrIssuesPropertyValues(Meta metaData) {
        return getTagPropertyValues(metaData, "issue");
    }

    private List<TestTag> getFeatureOrFeaturesPropertyValues(Meta metaData) {
        List<String> features = getTagPropertyValues(metaData, "feature");
        return convert(features, toFeatureTags());
    }

    private List<TestTag> getEpicOrEpicsPropertyValues(Meta metaData) {
        List<String> epics = getTagPropertyValues(metaData, "epic");
        return convert(epics, toEpicTags());
    }

    private List<TestTag> getTagOrTagsPropertyValues(Meta metaData) {
        List<String> tags = getTagPropertyValues(metaData, "tag");
        return convert(tags, toTags());
    }

    private Converter<String, TestTag> toTags() {
        return new Converter<String, TestTag>() {
            public TestTag convert(String tag) {
                List<String> tagParts = Lists.newArrayList(Splitter.on(":").trimResults().split(tag));
                if (tagParts.size() == 2) {
                    return TestTag.withName(tagParts.get(1)).andType(tagParts.get(0));
                } else {
                    return TestTag.withName("true").andType(tagParts.get(0));
                }
            }
        };
    }

    private Converter<String, TestTag> toFeatureTags() {
        return new Converter<String, TestTag>() {
            public TestTag convert(String featureName) {
                return TestTag.withName(featureName).andType("feature");
            }
        };
    }

    private Converter<String, TestTag> toEpicTags() {
        return new Converter<String, TestTag>() {
            public TestTag convert(String featureName) {
                return TestTag.withName(featureName).andType("epic");
            }
        };
    }

    private List<String> getTagPropertyValues(Meta metaData, String tagType) {
        String singularTag = metaData.getProperty(tagType);
        String pluralTagType = Inflector.getInstance().pluralize(tagType);

        String multipleTags = metaData.getProperty(pluralTagType);
        String allTags = Joiner.on(',').skipNulls().join(singularTag, multipleTags);

        return Lists.newArrayList(Splitter.on(',').omitEmptyStrings().trimResults().split(allTags));
    }

    private void registerIssues(Meta metaData) {
        List<String> issues = getIssueOrIssuesPropertyValues(metaData);

        if (!issues.isEmpty()) {
            StepEventBus.getEventBus().addIssuesToCurrentTest(issues);
        }
    }

    private void registerStoryIssues(Meta metaData) {
        List<String> issues = getIssueOrIssuesPropertyValues(metaData);

        if (!issues.isEmpty()) {
            StepEventBus.getEventBus().addIssuesToCurrentStory(issues);
        }
    }

    private void registerFeaturesAndEpics(Meta metaData) {
        List<TestTag> featuresAndEpics = featureAndEpicTags(metaData);

        if (!featuresAndEpics.isEmpty()) {
            StepEventBus.getEventBus().addTagsToCurrentTest(featuresAndEpics);
        }
    }

    private List<TestTag> featureAndEpicTags(Meta metaData) {
        List<TestTag> featuresAndEpics = Lists.newArrayList();
        featuresAndEpics.addAll(getFeatureOrFeaturesPropertyValues(metaData));
        featuresAndEpics.addAll(getEpicOrEpicsPropertyValues(metaData));
        return featuresAndEpics;
    }

    private void registerStoryFeaturesAndEpics(Meta metaData) {
        List<TestTag> featuresAndEpics = featureAndEpicTags(metaData);

        if (!featuresAndEpics.isEmpty()) {
            StepEventBus.getEventBus().addTagsToCurrentStory(featuresAndEpics);
        }
    }

    private void registerTags(Meta metaData) {
        List<TestTag> tags = getTagOrTagsPropertyValues(metaData);

        if (!tags.isEmpty()) {
            StepEventBus.getEventBus().addTagsToCurrentTest(tags);
        }
    }

    private Map<String, String> getMetadataFrom(Meta metaData) {
        Map<String, String> metadata = Maps.newHashMap();
        for (String propertyName : metaData.getPropertyNames()) {
            metadata.put(propertyName, metaData.getProperty(propertyName));
        }
        return metadata;
    }

    private void registerMetadata(Meta metaData) {
        Serenity.getCurrentSession().clearMetaData();

        Map<String, String> scenarioMetadata = getMetadataFrom(metaData);
        scenarioMetadata.putAll(storyMetadata);
        for (String key : scenarioMetadata.keySet()) {
            Serenity.getCurrentSession().addMetaData(key, scenarioMetadata.get(key));
        }
    }

    private void registerStoryTags(Meta metaData) {
        List<TestTag> tags = getTagOrTagsPropertyValues(metaData);

        if (!tags.isEmpty()) {
            StepEventBus.getEventBus().addTagsToCurrentStory(tags);
        }
    }

    private void registerStoryMeta(Meta metaData) {
        if (isPending(metaData)) {
            forcedStoryResult = Optional.of(TestResult.PENDING);
            StepEventBus.getEventBus().suspendTest();
        } else if (isSkipped(metaData)) {
            forcedStoryResult = Optional.of(TestResult.SKIPPED);
            StepEventBus.getEventBus().suspendTest();
        }
    }

    private void registerScenarioMeta(Meta metaData) {
        if (isPending(metaData)) {
            forcedScenarioResult = Optional.of(TestResult.PENDING);
        } else if (isSkipped(metaData)) {
            forcedScenarioResult = Optional.of(TestResult.SKIPPED);
        }
    }

    private String removeSuffixFrom(String name) {
        return (name.contains(".")) ? name.substring(0, name.indexOf(".")) : name;
    }

    public void afterStory(boolean given) {
        shouldNestScenarios(false);
        if (given) {
            givenStoryMonitor.exitingGivenStory();
            givenStoryDone(currentStory());
        } else {
            if (isAfterStory(currentStory())) {
                closeBrowsersForThisStory();
                generateReports();
            } else if (!isFixture(currentStory()) && !given && (!isAStoryLevelGiven(currentStory()))) {
                StepEventBus.getEventBus().testSuiteFinished();
                clearListeners();
            }
        }
        storyStack.pop();
    }

    private void closeBrowsersForThisStory() {
        if (!systemConfiguration.getUseUniqueBrowser()) {
            ThucydidesWebDriverSupport.closeAllDrivers();
        }
    }

    private boolean isAfterStory(Story currentStory) {
        return (currentStory.getName().equals("AfterStories"));
    }

    private synchronized void generateReports() {
        getReportService().generateReportsFor(getAllTestOutcomes());
    }

    public List<TestOutcome> getAllTestOutcomes() {
        return flatten(extract(baseStepListeners, on(BaseStepListener.class).getTestOutcomes()));
    }

    public void narrative(Narrative narrative) {
    }

    public void lifecyle(Lifecycle lifecycle) {
    }

    public void scenarioNotAllowed(Scenario scenario, String s) {
        StepEventBus.getEventBus().testIgnored();
    }

    private void startScenarioCalled(String scenarioTitle) {
        StepEventBus.getEventBus().testStarted(scenarioTitle);
        activeScenarios.add(scenarioTitle);
    }

    private boolean shouldRestartDriverBeforeEachScenario() {
        return systemConfiguration.getEnvironmentVariables().getPropertyAsBoolean(
                SerenityJBehaveSystemProperties.RESTART_BROWSER_EACH_SCENARIO.getName(), false);
    }

    private boolean shouldResetStepsBeforeEachScenario() {
        return systemConfiguration.getEnvironmentVariables().getPropertyAsBoolean(
                SerenityJBehaveSystemProperties.RESET_STEPS_EACH_SCENARIO.getName(), true);
    }


    public void scenarioMeta(Meta meta) {
        registerIssues(meta);
        registerFeaturesAndEpics(meta);
        registerTags(meta);
        registerMetadata(meta);
        registerScenarioMeta(meta);
    }

    private boolean isPending(Meta metaData) {
        return (metaData.hasProperty(PENDING));
    }

    private boolean isSkipped(Meta metaData) {
        return (metaData.hasProperty(WIP)|| metaData.hasProperty(SKIP));
    }

    public void afterScenario() {
        if (givenStoryMonitor.isInGivenStory() || shouldNestScenarios()) {
            StepEventBus.getEventBus().stepFinished();
        } else {
            StepEventBus.getEventBus().testFinished();
            if (isPendingScenario() || isPendingStory()) {
                StepEventBus.getEventBus().setAllStepsTo(TestResult.PENDING);
            }
            if (isSkippedScenario() || isSkippedStory()) {
                StepEventBus.getEventBus().setAllStepsTo(TestResult.SKIPPED);
            }
            activeScenarios.pop();
        }
    }

    private boolean isPendingScenario() {
        return forcedScenarioResult.or(TestResult.UNDEFINED) == TestResult.PENDING;
    }

    private boolean isSkippedScenario() {
        return forcedScenarioResult.or(TestResult.UNDEFINED) == TestResult.SKIPPED;
    }

    private boolean isPendingStory() {
        return forcedStoryResult.or(TestResult.UNDEFINED) == TestResult.PENDING;
    }

    private boolean isSkippedStory() {
        return forcedStoryResult.or(TestResult.UNDEFINED) == TestResult.SKIPPED;
    }


    public void givenStories(GivenStories givenStories) {
        givenStoryMonitor.enteringGivenStory();
    }

    public void givenStories(List<String> strings) {
    }

    List<Map<String, String>> exampleData;
    int exampleCount = 0;

    public void beforeExamples(List<String> steps, ExamplesTable table) {
        exampleCount = 0;
        exampleData = ImmutableList.copyOf(table.getRows());
        StepEventBus.getEventBus().useExamplesFrom(serenityTableFrom(table));
    }

    private DataTable serenityTableFrom(ExamplesTable table) {
        return DataTable.withHeaders(table.getHeaders()).andMappedRows(table.getRows()).build();

    }

    public void example(Map<String, String> tableRow) {
        if (shouldRestartDriverBeforeEachScenario()) {
            WebdriverProxyFactory.resetDriver(ThucydidesWebDriverSupport.getDriver());
        }

        StepEventBus.getEventBus().clearStepFailures();
        if (executingExamples()) {
            finishExample();
        }
        restartPeriodically();
        startExample();
    }

    private void startExample() {
        Map<String, String> data = exampleData.get(exampleCount - 1);
        StepEventBus.getEventBus().exampleStarted(data);
    }

    private void finishExample() {
        StepEventBus.getEventBus().exampleFinished();
    }

    private boolean executingExamples() {
        return (exampleCount > 0);
    }

    private void restartPeriodically() {
        exampleCount++;
        if (systemConfiguration.getRestartFrequency() > 0) {
            if (exampleCount % systemConfiguration.getRestartFrequency() == 0) {
                WebdriverProxyFactory.resetDriver(ThucydidesWebDriverSupport.getDriver());
            }
        }
    }

    public void afterExamples() {
        finishExample();
    }

    public void beforeStep(String stepTitle) {
        StepEventBus.getEventBus().stepStarted(ExecutedStepDescription.withTitle(stepTitle));
    }

    public void successful(String title) {
        if (annotatedResultTakesPriority()) {
            processAnnotatedResult(title);
        } else{
            StepEventBus.getEventBus().updateCurrentStepTitle(normalized(title));
            StepEventBus.getEventBus().stepFinished();
        }
    }

    private void processAnnotatedResult(String title) {
        TestResult forcedResult = StepEventBus.getEventBus().getForcedResult().get();
        switch (forcedResult) {
            case PENDING:
                StepEventBus.getEventBus().stepPending();
                break;
            case IGNORED:
                StepEventBus.getEventBus().stepIgnored();
                break;
            case SKIPPED:
                StepEventBus.getEventBus().stepIgnored();
                break;
            default:
                StepEventBus.getEventBus().stepIgnored();
        }

    }

    private boolean annotatedResultTakesPriority() {
        return StepEventBus.getEventBus().getForcedResult().isPresent();
    }

    public void ignorable(String title) {
        StepEventBus.getEventBus().updateCurrentStepTitle(normalized(title));
        StepEventBus.getEventBus().stepIgnored();
    }

    public void pending(String stepTitle) {
        StepEventBus.getEventBus().stepStarted(ExecutedStepDescription.withTitle(normalized(stepTitle)));
        StepEventBus.getEventBus().stepPending();

    }

    public void notPerformed(String stepTitle) {
        StepEventBus.getEventBus().stepStarted(ExecutedStepDescription.withTitle(normalized(stepTitle)));
        StepEventBus.getEventBus().stepIgnored();
    }

    public void failed(String stepTitle, Throwable cause) {
        Throwable rootCause = cause.getCause() != null ? cause.getCause() : cause;
        StepEventBus.getEventBus().updateCurrentStepTitle(stepTitle);
        if (isAssumptionFailure(rootCause)) {
            StepEventBus.getEventBus().assumptionViolated(rootCause.getMessage());
        } else {
            StepEventBus.getEventBus().stepFailed(new StepFailure(ExecutedStepDescription.withTitle(normalized(stepTitle)), rootCause));
        }
    }

    private boolean isAssumptionFailure(Throwable rootCause) {
        return (AssumptionViolatedException.class.isAssignableFrom(rootCause.getClass()));
    }

    public void failedOutcomes(String s, OutcomesTable outcomesTable) {
    }

    public void restarted(String s, Throwable throwable) {
    }

    public void dryRun() {
    }

    public void pendingMethods(List<String> strings) {
    }

    private String normalized(String value) {
        return value.replaceAll(OPEN_PARAM_CHAR, "{").replaceAll(CLOSE_PARAM_CHAR, "}");

    }
}
