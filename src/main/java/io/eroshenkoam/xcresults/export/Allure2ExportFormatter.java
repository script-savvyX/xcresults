package io.eroshenkoam.xcresults.export;

import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.model.Attachment;
import io.qameta.allure.model.ExecutableItem;
import io.qameta.allure.model.Label;
import io.qameta.allure.model.Link;
import io.qameta.allure.model.Status;
import io.qameta.allure.model.StatusDetails;
import io.qameta.allure.model.StepResult;
import io.qameta.allure.model.TestResult;
import org.apache.commons.io.FilenameUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.eroshenkoam.xcresults.export.ExportCommand.FILE_EXTENSION_HEIC;
import static io.eroshenkoam.xcresults.util.FormatUtil.getAttachmentFileName;
import static io.eroshenkoam.xcresults.util.FormatUtil.parseDate;
import static java.util.Objects.isNull;

public class Allure2ExportFormatter implements ExportFormatter {

    private static final String IDENTIFIER = "identifier";
    private static final String DURATION = "duration";
    private static final String STATUS = "testStatus";
    private static final String FAILURE_SUMMARIES = "failureSummaries";

    private static final String FAILURE_IS_TOP_LEVEL = "isTopLevelFailure";

    private static final String ACTIVITY_SUMMARIES = "activitySummaries";
    private static final String ACTIVITY_TYPE = "activityType";
    private static final String ACTIVITY_UUID = "uuid";
    private static final String ACTIVITY_TITLE = "title";
    private static final String ACTIVITY_START = "start";
    private static final String ACTIVITY_FINISH = "finish";
    private static final String ACTIVITY_FAILURE_SUMMARY_IDS = "failureSummaryIDs";

    private static final String FAILURE_MESSAGE = "message";
    private static final String FAILURE_TIMESTAMP = "timestamp";

    private static final String SOURCE_CODE_CONTEXT = "sourceCodeContext";
    private static final String SYMBOL_INFO = "symbolInfo";
    private static final String CALL_STACK = "callStack";
    private static final String LOCATION = "location";
    private static final String FILE_PATH = "filePath";
    private static final String LINE_NUMBER = "lineNumber";

    private static final String SUBACTIVITIES = "subactivities";

    private static final String ATTACHMENTS = "attachments";

    private static final String NAME = "name";
    private static final String FILENAME = "filename";
    private static final String VALUE = "_value";
    private static final String VALUES = "_values";

    private static final String SUITE = "suite";

    @Override
    public TestResult format(final ExportMeta meta, final JsonNode node) {
        final TestResult result = new TestResult()
                .setParameters(new ArrayList<>())
                .setLabels(new ArrayList<>())
                .setSteps(new ArrayList<>())
                .setAttachments(new ArrayList<>());
        if (node.has(NAME)) {
            result.setName(node.get(NAME).get(VALUE).asText());
        }
        if (node.has(IDENTIFIER)) {
            final String identifier = node.get(IDENTIFIER).get(VALUE).asText();
            result.setHistoryId(getHistoryId(meta, identifier));
            result.setFullName(identifier);
        }
        if (node.has(STATUS)) {
            result.setStatus(getTestStatus(node));
        }
        final StepContext context = new StepContext()
                .setResult(result)
                .setCurrent(result)
                .setPath(Collections.singletonList(result));
        context.setFailures(new HashMap<>());
        if (node.has(FAILURE_SUMMARIES)) {
            node.get(FAILURE_SUMMARIES).get(VALUES).forEach(failure -> {
                final String key = failure.get(ACTIVITY_UUID).get(VALUE).asText();
                context.getFailures().put(key, failure);
            });
        }
        if (node.has(ACTIVITY_SUMMARIES)) {
            final Iterable<JsonNode> activities = node.get(ACTIVITY_SUMMARIES).get(VALUES);
            for (JsonNode activity : activities) {
                parseStep(activity, context);
            }
        }
        final Optional<StepResult> topLevelFailure = context.getFailures().values().stream()
                .filter(this::isTopLevelFailure)
                .map(this::getFailureStep)
                .findFirst();
        if (topLevelFailure.isPresent()) {
            final StepResult failStep = topLevelFailure.get();
            final List<StepResult> steps = context.getResult().getSteps();
            steps.add(getPosition(steps, failStep), failStep);
            result.setStatus(failStep.getStatus());
            result.setStatusDetails(failStep.getStatusDetails());
        }
        meta.getLabels().forEach((name, value) -> {
            result.getLabels().add(new Label().setName(name).setValue(value));
        });
        if (Objects.isNull(result.getStart())) {
            result.setStart(meta.getStart());
        }
        if (Objects.nonNull(result.getStart())) {
            if (node.has(DURATION)) {
                final Double durationText = node.get(DURATION).get(VALUE).asDouble();
                long durationToMillis = (long) (durationText * 1000);
                result.setStop(result.getStart() + durationToMillis);
            }
            if (result.getSteps().size() > 0) {
                result.setStart(result.getSteps().get(0).getStart());
                result.setStop(result.getSteps().get(result.getSteps().size() - 1).getStop());
            }
        }
        return result;
    }

    @SuppressWarnings("PMD.NcssCount")
    private void parseStep(final JsonNode activity,
                           final StepContext context) {
        final Optional<String> title = getActivityTitle(activity);
        if (!title.isPresent()) {
            return;
        }
        final String activityTitle = title.get();

        final Matcher idMatcher = Pattern.compile("allure\\.id:(?<id>.*)")
                .matcher(activityTitle);
        if (idMatcher.matches()) {
            final Label label = new Label()
                    .setName("AS_ID")
                    .setValue(idMatcher.group("id"));
            context.getResult().getLabels().add(label);
            return;
        }
        final Matcher nameMatcher = Pattern.compile("allure\\.name:(?<name>.*)")
                .matcher(activityTitle);
        if (nameMatcher.matches()) {
            context.getResult().setName(nameMatcher.group("name"));
            return;
        }
        final Matcher descriptionMatcher = Pattern.compile("allure\\.description:(?<description>.*)")
                .matcher(activityTitle);
        if (descriptionMatcher.matches()) {
            context.getResult().setDescription(descriptionMatcher.group("description"));
            return;
        }
        final Matcher descriptionHtmlMatcher = Pattern.compile("allure\\.descriptionHtml:(?<descriptionHtml>.*)")
                .matcher(activityTitle);
        if (descriptionHtmlMatcher.matches()) {
            context.getResult().setDescriptionHtml(descriptionHtmlMatcher.group("descriptionHtml"));
            return;
        }
        final Matcher labelMatcher = Pattern.compile("allure\\.label\\.(?<name>.*?):(?<value>.*)")
                .matcher(activityTitle);
        if (labelMatcher.matches()) {
            final Label label = new Label()
                    .setName(labelMatcher.group("name"))
                    .setValue(labelMatcher.group("value").trim());
            context.getResult().getLabels().add(label);
            return;
        }
        final Matcher linkMatcher = Pattern.compile("allure\\.link\\.(?<name>.*?)(|\\[(?<type>.*)]):(?<url>.*)")
                .matcher(activityTitle);
        if (linkMatcher.matches()) {
            final Link link = new Link()
                    .setName(linkMatcher.group("name"))
                    .setType(linkMatcher.group("type"))
                    .setUrl(linkMatcher.group("url").trim());
            context.getResult().getLinks().add(link);
            return;
        }

        final Optional<List<Attachment>> attachments = Optional.ofNullable(activity.get(ATTACHMENTS))
                .map(a -> a.get(VALUES))
                .map(this::getAttachments);
        if (activityTitle.startsWith("Start Test at") && activity.has(ACTIVITY_START)) {
            context.getResult().setStart(parseDate(activity.get(ACTIVITY_START).get(VALUE).asText()));
            attachments.ifPresent(context.getCurrent().getAttachments()::addAll);
            return;
        }

        final StepResult step = new StepResult()
                .setName(activityTitle)
                .setStatus(Status.PASSED)
                .setSteps(new ArrayList<>())
                .setAttachments(new ArrayList<>());
        attachments.ifPresent(step.getAttachments()::addAll);

        final boolean hasAssertionMessage = activityTitle.startsWith("Assertion Failure")
                || activityTitle.contains("Test skipped");
        final boolean hasAssertionType = activity.has(ACTIVITY_TYPE)
                && activity.get(ACTIVITY_TYPE).get(VALUE).asText().contains("testAssertionFailure");
        if (hasAssertionMessage || hasAssertionType) {
            final Status status = context.getResult().getStatus();
            final StatusDetails details = new StatusDetails();

            if (isNull(status)) {
                details.setMessage("xcresults export tool issue: unsupported `testStatus` value found inside xcresult report");
            } else {
                details.setMessage(activityTitle);
            }

            step.setStatusDetails(details);
            step.setStatus(status);

            context.getPath().forEach(item -> {
                item.setStatusDetails(details);
                item.setStatus(status);
            });
        }
        if (activity.has(ACTIVITY_START) && activity.has(ACTIVITY_FINISH)) {
            step.setStart(parseDate(activity.get(ACTIVITY_START).get(VALUE).asText()));
            step.setStop(parseDate(activity.get(ACTIVITY_FINISH).get(VALUE).asText()));
        }
        if (activity.has(SUBACTIVITIES)) {
            for (JsonNode subActivity : activity.get(SUBACTIVITIES).get(VALUES)) {
                parseStep(subActivity, context.child(step));
            }
        }
        if (activity.has(ACTIVITY_FAILURE_SUMMARY_IDS)) {
            final Iterable<JsonNode> activityFailures = activity.get(ACTIVITY_FAILURE_SUMMARY_IDS).get(VALUES);
            for (JsonNode activityFailureUuid : activityFailures) {
                final String uuid = activityFailureUuid.get(VALUE).asText();
                final StepResult failureStep = getFailureStep(context.getFailures().get(uuid));
                step.getSteps().add(failureStep);
                step.setStatus(failureStep.getStatus());
                step.setStatusDetails(failureStep.getStatusDetails());
                context.getPath().forEach(item -> {
                    item.setStatusDetails(failureStep.getStatusDetails());
                    item.setStatus(failureStep.getStatus());
                });
            }
        }
        context.getCurrent().getSteps().add(step);
    }

    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    private List<Attachment> getAttachments(final Iterable<JsonNode> nodes) {
        final List<Attachment> attachments = new ArrayList<>();
        for (JsonNode node : nodes) {
            final String originalFileName = node.get(FILENAME).get(VALUE).asText();
            final String fileExtension = FilenameUtils.getExtension(originalFileName);
            final String sources = getAttachmentFileName(fileExtension);
            final String fileName = FILE_EXTENSION_HEIC.equals(fileExtension)
                    ? String.format("%s.%s", FilenameUtils.getBaseName(originalFileName), "jpeg")
                    : originalFileName;
            final Attachment attachment = new Attachment()
                    .setSource(sources)
                    .setName(fileName);
            attachments.add(attachment);
        }
        return attachments;
    }

    private int getPosition(final List<StepResult> steps, final StepResult step) {
        int position = 0;
        for (int i = 0; i < steps.size(); i++) {
            final StepResult prevStep = steps.get(i == 0 ? 0 : i - 1);
            final StepResult currStep = steps.get(i);
            if (prevStep.getStop() <= step.getStart() && step.getStop() <= currStep.getStart()) {
                position = i;
                break;
            }
        }
        return position;
    }

    private Status getTestStatus(final JsonNode node) {
        final String status = node.get(STATUS).get(VALUE).asText();
        if (isNull(status)) {
            return null;
        }

        switch (status) {
            case "Success":
            case "Expected Failure":
                return Status.PASSED;
            case "Failure":
                return Status.FAILED;
            case "Skipped":
                return Status.SKIPPED;
            default:
                return null;
        }
    }

    private Optional<String> getActivityTitle(final JsonNode node) {
        if (node.has(ACTIVITY_TITLE)) {
            return Optional.of(node.get(ACTIVITY_TITLE).get(VALUE).asText());
        }
        if (node.has(ACTIVITY_TYPE)) {
            return Optional.of(node.get(ACTIVITY_TYPE).get(VALUE).asText());
        }
        return Optional.empty();
    }

    private Boolean isTopLevelFailure(final JsonNode activityFailure) {
        if (activityFailure.has(FAILURE_IS_TOP_LEVEL)) {
            return activityFailure.get(FAILURE_IS_TOP_LEVEL).get(VALUE).asBoolean();
        }
        return false;
    }

    private StepResult getFailureStep(final JsonNode activityFailure) {
        final Long timestamp = parseDate(activityFailure.get(FAILURE_TIMESTAMP).get(VALUE).asText());
        final String message = activityFailure.get(FAILURE_MESSAGE).get(VALUE).asText();
        final String trace = getStackTrace(activityFailure);
        final Status failedStatus = Status.FAILED;
        final StatusDetails failedDetails = new StatusDetails()
                .setMessage(message)
                .setTrace(trace);
        final StepResult failureStep = new StepResult()
                .setStatus(failedStatus)
                .setName(message)
                .setStart(timestamp)
                .setStop(timestamp);
        failureStep.setStatusDetails(failedDetails);
        if (activityFailure.has(ATTACHMENTS)) {
            failureStep.getAttachments().addAll(getAttachments(activityFailure.get(ATTACHMENTS).get(VALUES)));
        }
        return failureStep;
    }

    private String getStackTrace(final JsonNode activityFailure) {
        if (activityFailure.has(SOURCE_CODE_CONTEXT)) {
            final JsonNode context = activityFailure.get(SOURCE_CODE_CONTEXT);
            if (context.has(CALL_STACK)) {
                final List<String> lines = new ArrayList<>();
                for (JsonNode line : context.findValue(CALL_STACK).get(VALUES)) {
                    Optional.ofNullable(line.get(SYMBOL_INFO))
                            .map(v -> v.findValue(LOCATION))
                            .flatMap(this::getFileLineNumber)
                            .ifPresent(lines::add);
                }
                return String.join("\n", lines);
            }
        }
        return null;
    }

    private Optional<String> getFileLineNumber(final JsonNode location) {
        if (location.has(FILE_PATH) && location.has(LINE_NUMBER)) {
            final String filePath = location.get(FILE_PATH).get(VALUE).asText();
            final String lineNumber = location.get(LINE_NUMBER).get(VALUE).asText();
            return Optional.of(String.format("%s:%s", filePath, lineNumber));
        }
        return Optional.empty();
    }

    private class StepContext {

        private TestResult result;
        private ExecutableItem current;
        private List<ExecutableItem> path;
        private Map<String, JsonNode> failures;

        public TestResult getResult() {
            return result;
        }

        public StepContext setResult(TestResult result) {
            this.result = result;
            return this;
        }

        public ExecutableItem getCurrent() {
            return current;
        }

        public StepContext setCurrent(ExecutableItem current) {
            this.current = current;
            return this;
        }

        public List<ExecutableItem> getPath() {
            return path;
        }

        public StepContext setPath(List<ExecutableItem> path) {
            this.path = path;
            return this;
        }

        public Map<String, JsonNode> getFailures() {
            return this.failures;
        }

        public StepContext setFailures(final Map<String, JsonNode> failures) {
            this.failures = failures;
            return this;
        }

        public StepContext child(final ExecutableItem next) {
            final List<ExecutableItem> nextPath = new ArrayList<>(path);
            nextPath.add(next);
            return new StepContext()
                    .setResult(result)
                    .setCurrent(next)
                    .setPath(nextPath)
                    .setFailures(this.getFailures());

        }
    }

    private String getHistoryId(final ExportMeta meta, final String identifier) {
        final String suite = meta.getLabels().getOrDefault(SUITE, "Default");
        return String.format("%s/%s", suite, identifier);
    }

}
