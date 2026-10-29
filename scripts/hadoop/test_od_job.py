import json
import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).parent

class OfflineStatisticsTest(unittest.TestCase):
    def pipeline(self, records):
        raw = '\n'.join(json.dumps(r) for r in records) + '\n'
        mapped = subprocess.run([sys.executable, str(ROOT/'od_mapper.py')], input=raw, text=True, capture_output=True, check=True)
        sorted_lines = '\n'.join(sorted(mapped.stdout.splitlines()))+'\n'
        reduced = subprocess.run([sys.executable,str(ROOT/'od_reducer.py')],input=sorted_lines,text=True,capture_output=True,check=True)
        return [json.loads(line) for line in reduced.stdout.splitlines()]

    def record(self, **changes):
        value=dict(schemaVersion=1,taskCode='T1',originCity='北京',destinationCity='上海',status='COMPLETED',actualElapsedMinutes=120.5,actualDistanceKm=200,actualFuelLiters=40,anomalyCount=3,sampleStatus='PENDING_MATCH',sourceType='SIMULATED')
        value.update(changes)
        return value

    def test_od_isolation_and_feedback_denominator(self):
        records=self.pipeline([self.record(),self.record(taskCode='T2',status='CANCELLED',actualDistanceKm=None,actualFuelLiters=None,actualElapsedMinutes=None,anomalyCount=0),self.record(taskCode='T3',originCity='广州',destinationCity='武汉',sampleStatus='INVALID')])
        by_od={r['originCity']:r for r in records}
        self.assertEqual(by_od['北京']['taskCount'],2)
        self.assertEqual(by_od['北京']['completedFeedbackCount'],1)
        self.assertEqual(by_od['北京']['averageActualElapsedMinutes'],120.5)
        self.assertEqual(by_od['北京']['anomalyCount'],3)
        self.assertEqual(by_od['广州']['excludedSampleCount'],1)

    def test_missing_numeric_feedback_never_becomes_zero_average(self):
        row=self.pipeline([self.record(actualDistanceKm=-2)])[0]
        self.assertIsNone(row['averageActualDistanceKm'])
        self.assertEqual(row['taskCount'],1)

    def test_corrupt_input_fails_instead_of_publishing_partial_results(self):
        with self.assertRaises(subprocess.CalledProcessError):
            self.pipeline([self.record(schemaVersion=99)])
    def test_unreasonable_fuel_is_retained_but_excluded_from_averages(self):
        row=self.pipeline([self.record(actualDistanceKm=1,actualFuelLiters=50,sampleStatus='INVALID')])[0]
        self.assertIsNone(row['averageActualFuelLiters'])
        self.assertEqual(row['excludedSampleCount'],1)

    def test_impossible_elapsed_time_is_excluded_and_speed_boundary_is_inclusive(self):
        row=self.pipeline([self.record(actualElapsedMinutes=.25,actualDistanceKm=100,sampleStatus='INVALID')])[0]
        self.assertEqual(row['taskCount'],1)
        self.assertEqual(row['completedFeedbackCount'],0)
        self.assertIsNone(row['averageActualDistanceKm'])
        self.assertEqual(row['excludedSampleCount'],1)
        row=self.pipeline([self.record(actualElapsedMinutes=60,actualDistanceKm=150)])[0]
        self.assertEqual(row['completedFeedbackCount'],1)

if __name__=='__main__': unittest.main()
