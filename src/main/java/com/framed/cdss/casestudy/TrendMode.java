package com.framed.cdss.casestudy;
/**
 * Enumerates the mode of tends conditions supported:
 * <ul>
 *   <li>{@link #MEAN_STEP_DIFF}: The trend is measured by the mean difference between observed data points.</li>
 *   <li>{@link #REGRESSION_SLOPE}: The trend is measured by the best fitting slope (regression).</li>
 * </ul>
 */
public enum TrendMode { MEAN_STEP_DIFF, REGRESSION_SLOPE }

