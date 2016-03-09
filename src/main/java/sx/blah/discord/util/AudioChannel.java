package sx.blah.discord.util;

import org.tritonus.dsp.ais.AmplitudeAudioInputStream;
import sx.blah.discord.Discord4J;
import sx.blah.discord.api.IDiscordClient;
import sx.blah.discord.api.internal.DiscordVoiceWS;
import sx.blah.discord.handle.impl.events.AudioPlayEvent;
import sx.blah.discord.handle.impl.events.AudioQueuedEvent;
import sx.blah.discord.handle.impl.events.AudioStopEvent;
import sx.blah.discord.handle.impl.events.AudioUnqueuedEvent;

import javax.sound.sampled.*;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * This is used to interface with voice channels.
 */
public class AudioChannel {

	private final List<AudioMetaData> metaDataQueue = new CopyOnWriteArrayList<>();
	private final List<AmplitudeAudioInputStream> audioQueue = new CopyOnWriteArrayList<>();
	private volatile boolean isPaused = false;
	private volatile float volume = 1.0F;
	private final IDiscordClient client;

	public AudioChannel(IDiscordClient client) {
		this.client = client;
	}

	public void setVolume(float volume) {
		this.volume = volume;
	}

	/**
	 * Pauses the audio.
	 */
	public void pause() {
		if (!isPaused) {
			AudioMetaData data = metaDataQueue.get(0);
			client.getDispatcher().dispatch(new AudioStopEvent(audioQueue.get(0), data.fileSource, data.urlSource, data.format));
		}
		isPaused = true;
	}

	/**
	 * Resumes the audio if was paused.
	 */
	public void resume() {
		if (isPaused) {
			AudioMetaData data = metaDataQueue.get(0);
			client.getDispatcher().dispatch(new AudioPlayEvent(audioQueue.get(0), data.fileSource, data.urlSource, data.format));
		}
		isPaused = false;
	}

	/**
	 * Returns whether or not the audio is paused.
	 *
	 * @return True if paused, false if otherwise.
	 */
	public boolean isPaused() {
		return isPaused;
	}

	/**
	 * Skips the currently queued audio.
	 */
	public void skip() {
		if (audioQueue.size() > 0) {
			unqueue(0);
		}
	}

	/**
	 * Un-queues the audio at the specified position in the queue.
	 *
	 * @param index The index.
	 *
	 * @throws ArrayIndexOutOfBoundsException
	 */
	public void unqueue(int index) throws ArrayIndexOutOfBoundsException {
		AudioMetaData data = metaDataQueue.get(index);
		client.getDispatcher().dispatch(new AudioUnqueuedEvent(audioQueue.get(index), data.fileSource, data.urlSource, data.format));
		audioQueue.remove(index);
		metaDataQueue.remove(index);
	}

	/**
	 * Gets the size of the audio queue.
	 *
	 * @return The size.
	 */
	public int getQueueSize() {
		return audioQueue.size();
	}

	/**
	 * Queues a url to be streamed.
	 *
	 * @param url The url to stream.
	 */
	public void queueUrl(String url) {
		try {
			queueUrl(new URL(url));
		} catch (MalformedURLException e) {
			Discord4J.LOGGER.error("Discord Internal Exception", e);
		}
	}

	/**
	 * Queues a url to be streamed.
	 *
	 * @param url The url to stream.
	 */
	public void queueUrl(URL url) {
		try {
			metaDataQueue.add(new AudioMetaData(null, url, AudioSystem.getAudioFileFormat(url)));
			BufferedInputStream bis = new BufferedInputStream(url.openStream());
			queue(AudioSystem.getAudioInputStream(bis));
		} catch (IOException | UnsupportedAudioFileException e) {
			Discord4J.LOGGER.error("Discord Internal Exception", e);
		}
	}

	/**
	 * Queues a file to be streamed.
	 *
	 * @param file The file to be streamed.
	 */
	public void queueFile(String file) {
		queueFile(new File(file));
	}

	/**
	 * Queues a file to be streamed.
	 *
	 * @param file The file to be streamed.
	 */
	public void queueFile(File file) {
		try {
			metaDataQueue.add(new AudioMetaData(file, null, AudioSystem.getAudioFileFormat(file)));
			queue(AudioSystem.getAudioInputStream(file));
		} catch (UnsupportedAudioFileException | IOException e) {
			Discord4J.LOGGER.error("Discord Internal Exception", e);
		}
	}

	/**
	 * Queues an {@link AudioInputStream} to be streamed.
	 *
	 * @param inSource The input stream to be streamed.
	 */
	public void queue(AudioInputStream inSource) {
		if (inSource == null)
			throw new IllegalArgumentException("Cannot create an audio player from a null AudioInputStream!");

		AudioFormat baseFormat = inSource.getFormat();

		//Converts first to PCM data. If the data is already PCM data, this will not change anything.
		AudioFormat toPCM = new AudioFormat(
				AudioFormat.Encoding.PCM_SIGNED,
				baseFormat.getSampleRate(),//AudioConnection.OPUS_SAMPLE_RATE,
				baseFormat.getSampleSizeInBits() != -1 ? baseFormat.getSampleSizeInBits() : 16,
				baseFormat.getChannels(),
				//If we are given a frame size, use it. Otherwise, assume 16 bits (2 8bit shorts) per channel.
				baseFormat.getFrameSize() != -1 ? baseFormat.getFrameSize() : 2*baseFormat.getChannels(),
				baseFormat.getFrameRate() != -1 ? baseFormat.getFrameRate() : baseFormat.getSampleRate(),
				baseFormat.isBigEndian());
		AudioInputStream pcmStream = AudioSystem.getAudioInputStream(toPCM, inSource);

		//Then resamples to a sample rate of 48000hz and ensures that data is Big Endian.
		AudioFormat audioFormat = new AudioFormat(
				toPCM.getEncoding(),
				DiscordVoiceWS.OPUS_SAMPLE_RATE,
				toPCM.getSampleSizeInBits(),
				toPCM.getChannels(),
				toPCM.getFrameSize(),
				toPCM.getFrameRate(),
				true);

		AudioInputStream outputAudio = AudioSystem.getAudioInputStream(audioFormat, pcmStream);

		if (metaDataQueue.size() == audioQueue.size()) { //Meta data wasn't added, user directly queued an audio inputstream
			try {
				metaDataQueue.add(new AudioMetaData(null, null, AudioSystem.getAudioFileFormat(inSource)));
				return;
			} catch (UnsupportedAudioFileException | IOException e) {
				Discord4J.LOGGER.error("Discord Internal Exception", e);
			}
		}

		if (outputAudio != null) {
			audioQueue.add(new AmplitudeAudioInputStream(outputAudio));
			AudioMetaData data = metaDataQueue.get(metaDataQueue.size()-1);
			client.getDispatcher().dispatch(new AudioQueuedEvent(outputAudio, data.fileSource, data.urlSource, data.format));
		} else
			metaDataQueue.remove(metaDataQueue.size()-1);
	}


	/**
	 * Gets the PCM data that needs to be sent.
	 *
	 * @param length : How many MS of data needed to be sent.
	 * @return : The PCM data
	 */
	public byte[] getAudioData(int length) {
		if (isPaused)
			return null;

		if (audioQueue.size() > 0) {
			AmplitudeAudioInputStream data = audioQueue.get(0);
			AudioMetaData metaData = metaDataQueue.get(0);
			try {
				int amountRead;
				byte[] audio = new byte[length*data.getFormat().getFrameSize()];

				data.setAmplitudeLinear(volume);
				amountRead = data.read(audio, 0, audio.length);

				if (amountRead > -1) {

					if (!metaData.startedReading) {
						metaData.startedReading = true;
						client.getDispatcher().dispatch(new AudioPlayEvent(data, metaData.fileSource,
								metaData.urlSource, metaData.format));
					}
					return audio;
				} else {
					audioQueue.remove(0);
					metaDataQueue.remove(0);
					client.getDispatcher().dispatch(new AudioStopEvent(data, metaData.fileSource,
							metaData.urlSource, metaData.format));
					return getAudioData(length);
				}

			} catch (IOException e) {
				Discord4J.LOGGER.error("Discord Internal Exception", e);
			}
		}
		return null;
	}

	/**
	 * Provides a small amount of information regarding the audio being played.
	 */
	private class AudioMetaData {
		protected final File fileSource;
		protected final URL urlSource;
		protected final AudioFileFormat format;
		protected volatile boolean startedReading = false;

		public AudioMetaData(File fileSource, URL urlSource, AudioFileFormat format) {
			this.fileSource = fileSource;
			this.urlSource = urlSource;
			this.format = format;
		}
	}
}
