#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Reduce task observations into OD statistics. Output JSONL is read from HDFS."""
from __future__ import print_function
import json
import sys

def result(key, totals):
    od = json.loads(key)
    count = totals[1]
    return dict(originCity=od[0], destinationCity=od[1], taskCount=int(totals[0]),
                completedFeedbackCount=int(count),
                averageActualElapsedMinutes=round(totals[2] / count, 2) if count else None,
                averageActualDistanceKm=round(totals[3] / count, 2) if count else None,
                averageActualFuelLiters=round(totals[4] / count, 2) if count else None,
                anomalyCount=int(totals[5]), excludedSampleCount=int(totals[6]),
                simulatedTaskCount=int(totals[7]))

if __name__ == '__main__':
    current = None
    totals = [0.0] * 8
    for line in sys.stdin:
        key, value = line.rstrip('\n').split('\t', 1)
        if current is not None and key != current:
            print(json.dumps(result(current, totals), ensure_ascii=True))
            totals = [0.0] * 8
        current = key
        values = json.loads(value)
        if len(values) != 8:
            raise ValueError('Invalid mapped statistics')
        totals = [a + b for a, b in zip(totals, values)]
    if current is not None:
        print(json.dumps(result(current, totals), ensure_ascii=True))
