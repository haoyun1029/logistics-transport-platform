#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Hadoop Streaming mapper. Python 2.7/3 compatible; one raw task per line."""
from __future__ import print_function
import json
import math
import sys

def positive(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and not math.isnan(value) and not math.isinf(value) and value > 0

def map_task(record):
    if record.get('schemaVersion') != 1 or not record.get('taskCode') or not record.get('originCity') or not record.get('destinationCity'):
        raise ValueError('Invalid raw task schema')
    completed = record.get('status') == 'COMPLETED'
    duration = record.get('actualElapsedMinutes')
    distance = record.get('actualDistanceKm')
    fuel = record.get('actualFuelLiters')
    # Same conservative plausibility ceiling as Java FEEDBACK_PLAUSIBILITY_V1.
    # This is a data-quality bound, not the legal speed limit of any road.
    valid_feedback = (completed and positive(duration) and positive(distance) and positive(fuel)
                      and fuel / distance <= 2 and distance * 60 <= 150 * duration)
    anomalies = record.get('anomalyCount')
    if not isinstance(anomalies, (int, float)) or anomalies < 0 or anomalies != int(anomalies):
        raise ValueError('Invalid anomaly count')
    # Feedback remains an actual business metric even when route matching is pending.
    # Invalid numeric feedback is excluded from averages; exclusions are counted separately.
    value = [1, int(valid_feedback), duration if valid_feedback else 0,
             distance if valid_feedback else 0, fuel if valid_feedback else 0,
             int(anomalies), int(record.get('sampleStatus') == 'INVALID'),
             int(record.get('sourceType') == 'SIMULATED')]
    return json.dumps([record['originCity'], record['destinationCity']], ensure_ascii=True), value

if __name__ == '__main__':
    for line in sys.stdin:
        if not line.strip():
            continue
        try:
            key, value = map_task(json.loads(line))
            print(key + '\t' + json.dumps(value))
        except (ValueError, TypeError, KeyError):
            # Fail the job rather than silently publish incomplete statistics.
            sys.stderr.write('Invalid raw task record; statistics were not published.\n')
            sys.exit(2)
