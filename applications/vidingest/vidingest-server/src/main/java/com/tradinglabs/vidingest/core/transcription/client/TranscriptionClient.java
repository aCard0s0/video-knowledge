package com.tradinglabs.vidingest.core.transcription.client;

import com.tradinglabs.vidingest.core.transcription.dto.WhisperAsrResult;

import java.nio.file.Path;

/**
 * Speech-to-text for the TRANSCRIBE phase.
 *
 * <p>One method, two implementations, picked at call time by
 * {@link TranscriptionClientRouter} from {@code vidingest.transcription.provider}. The result
 * type keeps its {@code Whisper*} name because that is the vocabulary the persisted columns and
 * the {@code whisper.json} artifact endpoint already use; the shape is not specific to
 * whisper-asr-webservice, and the OpenAI {@code verbose_json} envelope populates it unchanged.
 */
public interface TranscriptionClient {

    /**
     * @param audioFile 16kHz mono PCM WAV produced by the phase's ffmpeg extract
     * @throws com.tradinglabs.vidingest.core.transcription.service.TranscriptionFailureException
     *         on a missing file, a transport failure, or an unparseable response
     */
    WhisperAsrResult transcribeToJson(Path audioFile);
}
