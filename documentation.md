# functionality

If sounds exist within a high-frequency range, PhoneEar attempts to decode data from the sound. If data is successfully decoded, it is parsed for metadata that describes its purpose, and the content of the data message is routed to an appropriate app to trigger an action.

## encoding data into sound

by modulating sound wave properties such as frequency, amplitude, or phase

### frequency-shift keying (FSK)

For encoding, we used a simple version of frequency-shift keying (FSK) modulation to encode information by varying the instantaneous frequency of a sound wave

To preserve sound quality and to support existing hardware, we implemented PhoneEar using unused and nearly inaudible frequencies from 17 kHz to 20 kHz. This range of frequencies can isolate data from major noises introduced by music and human speech. To achieve sufficient operating distance, we ensure adequate wave energy and signal-to-noise ratio (SNR)

### message translation into binary stream

Any ASCII message is translated into a binary stream. The binary stream is encapsulated into packets of 8 ITA2 characters (5 bits/character). A four-bit Hamming code is used to provide forward error correction. If the information is longer than 8 characters, it is separated into multiple packets, each of which has control bits that specify its ID and length

Messages can be sent in binary or other forms, e.g. decimal. Increasing the base of the number system allows for more compact message. In our implementation, we used decimal values and mapped them to discrete frequencies ranging from 18–19.8 kHz in 200 kHz intervals. Dedicated frequencies of 17.8 and 20 kHz are added to the message in order to robustly indicate its start and end, respectively.

### creating sound file

based on our testing, conflicting harmonies (which impact the quality of the original sound) can be avoided by playing the sound data on a single audio channel or by adjusting the amplitude ratio between the data track and the original sound. This step may not be necessary, depending on the quality of the original sound. Alternatively, sound data can be played as a standalone audio stream.

The two MP3 files were generated using a compression rate of 128 and 320 kbps respectively. To remove harmonic effects, the data was only played on the left channel for the three audio files

Volume ratio between data and sound was adjusted to 140% for all the tested files to ensure sufficient data volume

Using the lossless WAV file, our system successfully received all packets up to 14m away

audio compression did impact the performance of the system and as expected, data transfers completely failed with the 128 kbps MP3. Using the 320kbps MP3, the phone could receive ≥ 60% of the packets when held in front of the chest and against the ear, from up to 2m and 8m respectively.

### identifying the sound

A tone is identified for decoding if it appears longer than a certain amount of time (500ms) and its amplitude is greater than a threshold value (30% above the mean amplitude of the frequencies in the range from 15.8–17 kHz). We choose to use a 500ms pulse to ensure that data can be received from a good distance. Due to the fact that the pulse duration is proportional to wave energy, higher pulse duration introduces higher SNR and higher power (the received power varies inversely with the square of the distance), allowing data to be reliably transferred farther (our results provide a higher operating distance when compared to previous work). Our current implementation allows a speed of 8 bits per second, which is sufficient for sending 1 byte/sec. up to 18 m. Speed can be improved for apps requiring shorter operating distances. Our current implementation is meant to demonstrate the upper bound of PhoneEar’s working distance and the corresponding lower bound of data transformation speed.

### decoding message

Once a packet is received, it is converted into binary form. Single-bit errors are corrected using the Hamming code. If more errors occur, the packet is dropped. Since PhoneEar is a simplex system (the encoder only transmits and the decoder only listens), the transmitter continuously plays the message, broadcasting it until the end of a predetermined time. The encoder has no knowledge of whether it was received. The decoder, on the other hand, is aware of a packet being lost, and will then attempt to receive it in the next loop. Lost packets are identified by IDs.

#### error detection

#### error correction





## differences to the original paper

### packet structure

#### paper

meta header [

​	meta header start ('001')

​	message length (8 bits)

​	number of packets (5 bits)

​	meta header end ('100')

]

packet01 [

​	packet header start ('010')

​	packet id (5 bits)

​	packet header end ('011')

​	data (8x 5 bits)

]

packet.. [

packet header start ('010')

...

]

# challenges we encountered

## understanding the basics of signal processing

### amplitude



### sampling rate

> The measured frequency range is always 0 to 1/2 the Sampling Rate.
>
> The Sampling Rate determines how many times a second that the analog input signal is "sampled" or digitized by the input device.
>
> An important principal from digital signal processing is the "Nyquist Sampling Theorem" which states that any signal can be represented if sampled at least twice the rate of the highest frequency of interest.  This means that if you wish to measure a 3,000 Hz signal, the sampling rate must be greater than 6,000 Hz.  A note of caution: if there are unwanted signals which are greater than 3,000 Hz, Aliasing will occur.

source: https://www.spectraplus.com/DT_help/sampling_rate.htm

we want to be able to measure frequencies up to 20 kHz, so we chose the sampling rate of 44.1 kHz.

### fft size

> The selected FFT size directly affects the resolution of the resulting spectra. The number of spectral lines is always 1/2 of the selected FFT size. Thus a 1024 point FFT produces 512 output spectral lines.
>
> The frequency resolution of each spectral line is equal to the Sampling Rate divided by the FFT size.  For instance, if the FFT size is 1024 and the Sampling Rate is 8192, the resolution of each spectral line will be:
>
> ​    8192 / 1024 = 8 Hz.
>
> Larger FFT sizes provide higher spectral resolution but take longer to compute.

source: https://www.spectraplus.com/DT_help/fft_size.htm

in our case a resolution of around 100 Hz is enough, so we selected a fft size of 512, which results in a resolution of roughly 86 Hz.

### averaging for noise reduction

source: https://dsp.stackexchange.com/questions/8884/how-do-i-average-frequency-spectra



# unanswered

## buffer size

choose a larger size (ca. 1 sec) so that overrun is unlikely

## agc automatic gain control



## fft window (overlapped analyze window)