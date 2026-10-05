// SPDX-FileCopyrightText: 2025-2026 Institute of Medical Informatics, Medical Faculty, RWTH Aachen
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.framed.cdss.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class InterpreterUtils {
    public static List<String> getInterpreterInputChannelsList(String rrMismatchChannel,
                                                               String dislocationChannel,
                                                               String spo2LimitChannel,
                                                               String spo2TrendChannel,
                                                               String etCO2LimitChannel,
                                                               String hdArythChannel,
                                                               String piLimitChannel,
                                                               String hrLimitChannel,
                                                               String sfLimitChannel){
        return List.of(
                rrMismatchChannel,
                dislocationChannel,
                spo2LimitChannel,
                spo2TrendChannel,
                etCO2LimitChannel,
                hdArythChannel,
                piLimitChannel,
                hrLimitChannel,
                sfLimitChannel
        );
    }

    public static List<Map<String, String>> getInterpreterInputFiringRules(String rrMismatchChannel,
                                                               String dislocationChannel,
                                                               String spo2LimitChannel,
                                                               String spo2TrendChannel,
                                                               String etCO2LimitChannel,
                                                               String hdArythChannel,
                                                               String piLimitChannel,
                                                               String hrLimitChannel,
                                                               String sfLimitChannel){
        List<String> channels =  List.of(
                rrMismatchChannel,
                dislocationChannel,
                spo2LimitChannel,
                spo2TrendChannel,
                etCO2LimitChannel,
                hdArythChannel,
                piLimitChannel,
                hrLimitChannel,
                sfLimitChannel
        );
        List<Map<String, String>> firingRules = new ArrayList<>();
        for (String channel: channels) {
            firingRules.add(
                    Map.of(channel, "*")
            );
        }
        return firingRules;
    }
}
