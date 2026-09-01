package com.tradinglabs.vidingest.core.knowledge.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradinglabs.vidingest.api.knowledge.KnowledgeUnitType;
import com.tradinglabs.vidingest.core.knowledge.dto.KnowledgeUnitDraft;
import com.tradinglabs.vidingest.core.knowledge.service.KnowledgeExtractionFailureException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The parser had no tests, which is how the silent zero survived: its javadoc said it would "drop
 * the batch rather than fail the whole video", while {@code KnowledgeExtractionService} counts a
 * batch as failed only when {@code extract} throws. An unreadable payload therefore contributed
 * zero drafts, left {@code batchesFailed} at zero, and the wipe-and-replace went ahead — a phase
 * reporting success having extracted nothing.
 *
 * <p>Two behaviours are asserted together on purpose, because the fix is only correct if both hold:
 * an unreadable payload <b>fails</b>, and a well-formed empty array <b>does not</b>. Collapse the
 * distinction either way and the phase is broken in one direction or the other.
 */
class KnowledgeUnitJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static List<KnowledgeUnitDraft> parse(String content) {
        return KnowledgeUnitJson.parseUnitsArray(MAPPER, content);
    }

    // ---- the legitimate empty result, which must stay legitimate ----

    @Test
    void anEmptyUnitsArrayIsASuccessfulNothing() {
        assertThat(parse("{\"units\": []}")).isEmpty();
    }

    // ---- the shapes the fallbacks exist to recover ----

    @Test
    void readsThePreferredUnitsKey() {
        List<KnowledgeUnitDraft> drafts = parse("""
                {"units":[{"type":"PROCEDURE","title":"A method","content":"1. WHEN x THEN y",
                  "salience":0.9,"source_segment_indices":[0,1],"start_seconds":0.0,"end_seconds":30.0}]}
                """);

        assertThat(drafts).singleElement().satisfies(d -> {
            assertThat(d.type()).isEqualTo(KnowledgeUnitType.PROCEDURE);
            assertThat(d.title()).isEqualTo("A method");
            assertThat(d.content()).isEqualTo("1. WHEN x THEN y");
            assertThat(d.salience()).isEqualTo(0.9);
            assertThat(d.sourceSegmentIndices()).containsExactly(0, 1);
            assertThat(d.startSeconds()).isEqualTo(0.0);
            assertThat(d.endSeconds()).isEqualTo(30.0);
        });
    }

    /**
     * Weak open models drift to other root keys even with a schema attached — qwen2.5:7b was
     * observed emitting {@code {transcript: ...}}. These fallbacks are why the schema is
     * belt-and-braces rather than the only defence, so they are pinned rather than assumed.
     */
    @Test
    void recoversUnitsFromKnownAlternateRootKeys() {
        for (String key : List.of("knowledge_units", "knowledgeUnits", "items", "data", "results")) {
            String body = "{\"" + key + "\":[{\"type\":\"CLAIM\",\"content\":\"a claim\"}]}";
            assertThat(parse(body))
                    .withFailMessage("root key %s was not recovered", key)
                    .singleElement()
                    .extracting(KnowledgeUnitDraft::content).isEqualTo("a claim");
        }
    }

    @Test
    void recoversABareTopLevelArray() {
        assertThat(parse("[{\"type\":\"TOPIC\",\"content\":\"a topic\"}]"))
                .singleElement().extracting(KnowledgeUnitDraft::content).isEqualTo("a topic");
    }

    /** Last resort: an unknown key whose value still looks like units. */
    @Test
    void recoversUnitsFromAnUnrecognisedKeyThatLooksRight() {
        assertThat(parse("{\"extracted_stuff\":[{\"type\":\"ENTITY\",\"content\":\"a thing\"}]}"))
                .singleElement().extracting(KnowledgeUnitDraft::type).isEqualTo(KnowledgeUnitType.ENTITY);
    }

    @Test
    void acceptsCamelCaseFieldNamesAlongsideSnakeCase() {
        assertThat(parse("""
                {"units":[{"type":"SUMMARY","content":"c","startSeconds":1.5,"endSeconds":9.5,
                  "sourceSegmentIndices":[2],"entityType":"PRODUCT"}]}
                """))
                .singleElement().satisfies(d -> {
                    assertThat(d.startSeconds()).isEqualTo(1.5);
                    assertThat(d.endSeconds()).isEqualTo(9.5);
                    assertThat(d.sourceSegmentIndices()).containsExactly(2);
                    assertThat(d.entityType()).isEqualTo("PRODUCT");
                });
    }

    // ---- one bad unit is tolerable; the batch is not sacrificed for it ----

    @Test
    void dropsIndividuallyMalformedUnitsAndKeepsTheRest() {
        List<KnowledgeUnitDraft> drafts = parse("""
                {"units":[
                  {"type":"CLAIM","content":"kept"},
                  {"type":"NOT_A_TYPE","content":"unknown type"},
                  {"content":"no type at all"},
                  {"type":"CLAIM"},
                  {"type":"CLAIM","content":"   "},
                  {"type":"TOPIC","content":"also kept"}
                ]}
                """);

        assertThat(drafts).extracting(KnowledgeUnitDraft::content).containsExactly("kept", "also kept");
    }

    // ---- the silent zero, in each of the ways it used to happen ----

    @Test
    void failsOnTruncatedJsonRatherThanReportingNoUnits() {
        // What an output-cap overrun looks like: the model was mid-object when the budget ran out.
        String truncated = "{\"units\":[{\"type\":\"PROCEDURE\",\"title\":\"A method\",\"content\":\"1. WHEN x TH";

        assertThatThrownBy(() -> parse(truncated))
                .isInstanceOf(KnowledgeExtractionFailureException.class)
                .hasMessageContaining("not parseable JSON")
                // The cap is the usual cause and the message has to name it, or a too-low cap is
                // indistinguishable from a broken model in the logs.
                .hasMessageContaining("max-output-tokens")
                .hasMessageContaining(String.valueOf(truncated.length()));
    }

    @Test
    void failsWhenTheJsonIsValidButHasNoUnitsArray() {
        assertThatThrownBy(() -> parse("{\"transcript\":\"the model echoed the input\"}"))
                .isInstanceOf(KnowledgeExtractionFailureException.class)
                .hasMessageContaining("no recognisable units array")
                .hasMessageContaining("transcript");
    }

    @Test
    void failsWhenEveryUnitInANonEmptyArrayIsMalformed() {
        assertThatThrownBy(() -> parse("{\"units\":[{\"type\":\"NOPE\",\"content\":\"x\"},{\"content\":\"y\"}]}"))
                .isInstanceOf(KnowledgeExtractionFailureException.class)
                .hasMessageContaining("not one of them had a usable type and content");
    }

    @Test
    void failsWhenTheUnitsArrayIsNotAListOfObjects() {
        assertThatThrownBy(() -> parse("{\"units\":[\"just\",\"strings\"]}"))
                .isInstanceOf(KnowledgeExtractionFailureException.class);
    }

    @Test
    void failsOnContentThatIsNotJsonAtAll() {
        assertThatThrownBy(() -> parse("I'm sorry, I can't help with that."))
                .isInstanceOf(KnowledgeExtractionFailureException.class)
                .hasMessageContaining("not parseable JSON");
    }
}
