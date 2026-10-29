import copy
import json
import tempfile
import unittest
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).parent))
from validate_route_evidence import validate

TEMPLATE = json.loads((Path(__file__).with_name('route-evidence-template.json')).read_text(encoding='utf-8'))


def complete_bundle():
    data = copy.deepcopy(TEMPLATE)
    route = data['routes'][0]
    route.update(routeId='R1', originCity='甲市', destinationCity='乙市', routeStatus='PENDING')
    segment = route['segments'][0]
    segment.update(fromCity='甲市', toCity='乙市', roadName='测试道路', roadType='普通公路', distanceKm=10, estimatedMinutes=20, verifiedAt='2026-10-24T12:00:00Z', verifiedBy='reviewer-2')
    segment['geometry']['coordinates'] = [[110, 30], [110.1, 30.1]]
    segment['mapRequest'] = {'source': 'TEST', 'requestedAt': '2026-10-24T11:00:00Z', 'parameters': {'origin': '110,30'}}
    segment['mapResponse'] = {'status': 'OK', 'rawFile': 'raw.json', 'sha256': 'a' * 64}
    segment['truckEvidence'] = {'sourceUrl': 'https://example.invalid/evidence', 'sourceTitle': '测试来源', 'checkedAt': '2026-10-24T12:00:00Z', 'applicableVehicle': '4轴货车', 'conclusion': '允许通行'}
    return data


class RouteEvidenceValidationTest(unittest.TestCase):
    def test_template_stays_pending(self):
        errors = validate(TEMPLATE)
        self.assertTrue(errors)
        self.assertTrue(any('truckEvidence.sourceUrl' in error for error in errors))

    def test_complete_bundle_is_ready_for_human_review(self):
        self.assertEqual(validate(complete_bundle()), [])

    def test_duplicate_od_is_rejected(self):
        data = complete_bundle()
        duplicate = copy.deepcopy(data['routes'][0])
        duplicate['routeId'] = 'R2'
        data['routes'].append(duplicate)
        self.assertTrue(any('duplicates an origin/destination pair' in error for error in validate(data)))

    def test_non_contiguous_segments_are_rejected(self):
        data = complete_bundle()
        data['routes'][0]['segments'][0]['sequence'] = 2
        self.assertTrue(any('sequence must be contiguous' in error for error in validate(data)))

    def test_verified_status_is_rejected_at_intake(self):
        data = complete_bundle()
        data["routes"][0]["routeStatus"] = "VERIFIED"
        errors = validate(data)
        self.assertTrue(any("must be PENDING for intake" in error for error in errors))

    def test_verified_status_cannot_hide_errors(self):
        data = complete_bundle()
        data['routes'][0]['routeStatus'] = 'VERIFIED'
        data['routes'][0]['segments'][0]['truckEvidence']['sourceUrl'] = ''
        errors = validate(data)
        self.assertTrue(any("must be PENDING for intake" in error for error in errors))


if __name__ == '__main__':
    unittest.main()




