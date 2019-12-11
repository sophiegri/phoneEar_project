Bauhaus Universität Weimar  
Summer semester 2019  
Mobile Information Systems  

Research project based on "PhoneEar: Interactions for Mobile Devices that Hear High-Frequency Sound-Encoded Data"

Team members:  
Sophie Grimme  
Julien Breunig

# Project description

The goal of this project was programming an android app, that is capable of receiving and decoding high-frequency sound-encoded data. To accomplish this task, we had to understand the basics of signal processing and how to encode data into sound.

We used Android Studio 3.5 as programming environment and Audacity 2.3.2 for the creation of the encoded sound signals. Our main testing device was a LG nexus 5 running Android 7.1.2.

## Encoding data into sound

Sound consists out of waves. The sound wave properties frequency, amplitude and phase can be modulated.

### Frequency-shift keying (FSK)

Frequency-shift keying (FSK) modulation is one possibility to encode information by assigning values to certain frequencies. This method was also used in the original PhoneEar project. Each frequency shift or change of frequency represents a different signal. For example, the frequency of 500 Hz is assigned to 0, while the frequency of 1000 Hz is assigned to 1. Alternating between these two frequencies in a certain pattern translates to the same pattern in 0's and 1's.  
The selection of the used frequencies depends on the complexity of the message, who is supposed to be able to hear it and which device is supposed to be capable of reproducing them.  
A complex message requires more frequencies, because the encoding of a wide variety of symbols needs more data and if the message needs to be transmitted in the same time more channels are necessary.  
The message is supposed to be not audible by the human as it would be distracting from the normal content that is send at this time, that's the reason why we chose the same frequencies as the original paper, starting at 17.0 kHz.  
The highest used frequency is at 20.0 kHz, because common speakers are supposed to be able to reproduce the signals. This is already at the upper bound as many speakers' frequency response starts to lower at this point as speakers don't reproduce high frequencies at same loudness as midrange frequencies [Feinstein2016].  
This range of frequencies also has the advantage that it is isolated from major noises introduced by music and human speech, so it doesn't interfere with them and can be send at the same time [Nittala2015].

### Message translation into binary stream

Nittala et al. [Nittala2015] wanted to be able to send any ASCII message, so they encapsulated the binary stream into packets of 8 ITA2 characters (5 bits/character). They also used a four-bit Hamming code to provide forward error correction and separated the message into multiple packets, when the information was longer than 8 characters. As a result, their package structure needed a meta header containing the length of the message and the number of packets and then each packet had its own header containing the packet id. To make their messages more compact message, they increased the base of the number system to ten, the decimal system. "In our implementation, we used decimal values and mapped them to discrete frequencies ranging from 18–19.8 kHz in 200 kHz intervals. Dedicated frequencies of 17.8 and 20 kHz are added to the message in order to robustly indicate its start and end, respectively." [Nittala2015]

We followed their example for the frequencies but used a simpler packet structure by omitting the meta header and packet id as well as the error correction for now. Our signal contains a 100 ms hail frequency (17.0 kHz) to ensure that the receiver is in phase with the sender [Lopes2003], followed by a 900 ms starting signal (17.8 kHz). After this the message is send by alternating between a 100 ms hail frequency and 400 ms of the target frequency, which is described in the next paragraph. To end the message a 100 ms hail signal followed by 900 ms of the ending signal (20 kHz) is transmitted. 

For the encapsulation of the message we decided to use upper-case letters of the ASCII table in decimal base. This allowed for a simple pattern, since they all consist out of two numbers and therefore two consecutive signals were used for one letter. As an example, the letter A, encoded by the number 65, is sent using the frequency 19.2 kHz (6) followed by 19.0 kHz (5).

A single letter message takes 3 seconds to be transmitted this way and every additional letter adds another second.

### Creating of the sound file

Nittala et al. [Nittala2015] did some testing and found "conflicting harmonies (which impact the quality of the original sound) can be avoided by playing the sound data on a single audio channel or by adjusting the amplitude ratio between the data track and the original sound. This step may not be necessary, depending on the quality of the original sound. Alternatively, sound data can be played as a standalone audio stream." They adjusted the volume ratio between data and sound to 140% and played the data only on the left channel. Audio compression is another issue, which is why their best results were accomplished using the lossless wav-format.

For the creation of our test messages "ACHTUNG" and "PUDDING", we decided to play the encoded message as a separate audio stream, this allowed for an easy adjustment of the volume ratio.

Aside from that, we noticed audible clicking at the transitions between frequencies, a phenomenon also mentioned by Deshotels [Deshotels2014 p.7]. He solved the problem by "gradually reducing the amplitude at the beginning and end of each symbol. This caused the clicks to become so quiet that they are inaudible." This solution also worked for us.

To increase the chances of receiving the message, the message is sent repeatedly. In our case we repeat the message three times, since the airport announcement we found (https://www.youtube.com/watch?v=D0qmWzef-o4) is 27 seconds long and our seven-letter-test-messages takes 9 seconds to be sent once.

### Identifying the sound signals

Nittala et al. [Nittala2015] identified a tone for decoding "if it appears longer than a certain amount of time (500 ms) and its amplitude is greater than a threshold value (30% above the mean amplitude of the frequencies in the range from 15.8–17 kHz). We chose to use a 500 ms pulse to ensure that data can be received from a good distance. Due to the fact that the pulse duration is proportional to wave energy, higher pulse duration introduces higher SNR and higher power (the received power varies inversely with the square of the distance), allowing data to be reliably transferred farther (our results provide a higher operating distance when compared to previous work)."

As mentioned before, we increased the length of the starting and ending signal to 900 ms but shortened the other signals to 400 ms. Instead of requiring the tone to be above a certain threshold for the entire 500 ms, we approached the identification slightly different. We look at 50 ms time windows and extracted the frequency within our target frequencies after the FFT, that had the highest amplitude and had to be 10% above the mean amplitude of the frequencies in the range from 15.8–16.8 kHz at the same time. We store this results in an array and if a frequency reaches the count of 4 (half of the possible 8 measurements (400 ms / 50 ms)) it is detected as signal. After 10 measurements (500 ms) or if a hail frequency is detected the counter is reset.
If the first 4 measurements are already successfully identifying a signal, the following measurements are neglected until the next reset.

If no frequency reaches the count of 4 a visual representation ("_") is shown in the stream of detected signals, this way it is obvious to the user, that the signal wasn't received correctly.

To be able to do all of this we first needed to understand some basics of signal processing to set up the recording correctly and choose the right settings for the FFT.

#### Sampling rate

When recording sound, it is important to select a suitable sampling rate, especially when high frequencies are involved.

> The measured frequency range is always 0 to 1/2 the Sampling Rate.
>
> The Sampling Rate determines how many times a second that the analog input signal is "sampled" or digitized by the input device.
>
> An important principal from digital signal processing is the "Nyquist Sampling Theorem" which states that any signal can be represented if sampled at least twice the rate of the highest frequency of interest.  This means that if you wish to measure a 3,000 Hz signal, the sampling rate must be greater than 6,000 Hz.  A note of caution: if there are unwanted signals which are greater than 3,000 Hz, Aliasing will occur.

source: https://www.spectraplus.com/DT_help/sampling_rate.htm

We needed to be able to measure frequencies up to 20 kHz, so we chose the sampling rate of 44.1 kHz.

#### FFT size 

> The selected FFT size directly affects the resolution of the resulting spectra. The number of spectral lines is always 1/2 of the selected FFT size. Thus a 1024 point FFT produces 512 output spectral lines.
>
> The frequency resolution of each spectral line is equal to the Sampling Rate divided by the FFT size.  For instance, if the FFT size is 1024 and the Sampling Rate is 8192, the resolution of each spectral line will be:
>
> ​    8192 / 1024 = 8 Hz.
>
> Larger FFT sizes provide higher spectral resolution but take longer to compute.

source: https://www.spectraplus.com/DT_help/fft_size.htm

In our case a resolution of around 100 Hz is enough, because the difference between our target frequencies is 200 Hz. We selected a FFT size of 512, which results in a resolution of roughly 86 Hz.

### Decoding the message

Once an ending signal is received, the stream of data is converted into letters by the predefined pattern, two frequencies and their respective decimal number are translated into one letter. Missing signals are translated to a "_", which resembles a gap in the message, that can often be completed by the user looking at the message.

Since we lack the implementation of an error detection or correction code, we can't correct single-bit errors, like Nittala et al. [Nittala2015]. Because of their use of the package id, they also had the opportunity that "if more errors occur, the packet is dropped. Since PhoneEar is a simplex system (the encoder only transmits and the decoder only listens), the transmitter continuously plays the message, broadcasting it until the end of a predetermined time. The encoder has no knowledge of whether it was received. The decoder, on the other hand, is aware of a packet being lost, and will then attempt to receive it in the next loop. Lost packets are identified by IDs."

The repeated sending of the message should ideally enable the user to recover the entire message by combining missing information with the information from following repetitions.

Nittala et al. [Nittala2015] then used the decoded message to display notification for example or stopped the music playback, depending on the purpose given in the metadata. We only display the decoded message in a text view for the user to read. Only the implementation of a more advanced packet structure allows for a reliable use of the decoded message, while our solution requires the user to make sense out of the transmitted message in case errors occur.

<div style="page-break-after: always;"></div>

# References

Deshotels, Luke. 2014. Inaudible sound as a covert channel in mobile devices. In *Proceedings of the 8th USENIX conference on Offensive Technologies* (WOOT'14). USENIX Association, Berkeley, CA, USA, 16-16.

Feinstein, Steve. 2016. Understanding Frequency Response - Why it Matters. https://www.alesis.com/kb/article/2227. (visited on 03.09.19)

Gerasimov, Vadim & Bender, Walter. 2000. Things that talk: Using sound for device-to-device and device-to-human communication. IBM Systems Journal. 39. 530 - 546.

Lopes, Cristina Videira & Aguiar, Pedro M. Q.. 2003. Acoustic Modems for Ubiquitous Computing. *IEEE Pervasive Computing* 2, 3 (July 2003), 62-71.

Nittala, Aditya Shekhar & Yang, Xing-Dong & Bateman, Scott & Sharlin, Ehud & Greenberg, Saul. 2015. PhoneEar: interactions for mobile devices that hear high-frequency sound-encoded data. In *Proceedings of the 7th ACM SIGCHI Symposium on Engineering Interactive Computing Systems* (EICS '15). ACM, New York, NY, USA, 174-179.