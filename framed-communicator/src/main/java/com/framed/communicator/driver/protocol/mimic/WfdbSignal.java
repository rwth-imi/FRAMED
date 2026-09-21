package com.framed.communicator.driver.protocol.mimic;

/**
 * The specification of a single signal, as declared on one signal-specification line of a
 * <a href="https://archive.physionet.org/physiotools/wag/header-5.htm">WFDB header file</a>.
 *
 * <p>All fields are taken verbatim from the header; the physical value of a raw ADC sample is
 * recovered with {@link #toPhysical(int)} using the WFDB convention
 * {@code physical = (adc - baseline) / gain}. A {@code gain} of {@code 0} marks the signal as
 * <em>uncalibrated</em>: no physical unit is defined and the raw ADC value is passed through
 * unchanged (see {@link #uncalibrated()}).</p>
 *
 * @param fileName        name of the {@code .dat} file holding this signal's samples (relative to
 *                        the header), or {@code "~"} for a layout-header placeholder signal
 * @param format          WFDB storage format code (e.g. {@code 80}, {@code 16}, {@code 212})
 * @param byteOffset      preamble length in bytes to skip at the start of {@code fileName}
 *                        (the {@code +N} decorator on the format field; {@code 0} if absent)
 * @param gain            ADC units per physical unit; {@code 0} means uncalibrated
 * @param baseline        ADC value corresponding to a physical value of {@code 0}
 * @param units           physical unit string (e.g. {@code "mV"}, {@code "mmHg"}); may be {@code null}
 * @param description     human-readable signal name (e.g. {@code "II"}, {@code "PLETH"}, {@code "ABP"})
 */
public record WfdbSignal(
        String fileName,
        int format,
        int byteOffset,
        double gain,
        int baseline,
        String units,
        String description) {

    /**
     * Whether this signal is uncalibrated (WFDB gain {@code 0}). Uncalibrated signals carry no
     * physical unit and {@link #toPhysical(int)} returns the raw ADC value.
     *
     * @return {@code true} if {@link #gain()} is {@code 0}
     */
    public boolean uncalibrated() {
        return gain == 0.0;
    }

    /**
     * Converts a raw ADC sample to its physical value using {@code (adc - baseline) / gain}.
     *
     * @param adc the raw (already format-decoded, sign-corrected) ADC sample value
     * @return the physical value, or the unchanged {@code adc} when {@link #uncalibrated()}
     */
    public double toPhysical(int adc) {
        return uncalibrated() ? adc : (adc - baseline) / gain;
    }
}
