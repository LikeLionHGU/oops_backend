import copy
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from fixed_review_suite import digest,read
from private_report_bundle import save,load


class ReportBundleTests(unittest.TestCase):
    def test_roundtrip_preserves_logical_report_and_original_provider_objects(self):
        report=dict(schemaVersion='fixture',cases=[],calls=[dict(unitIndex=1,providerResponse={'content':'original'})])
        with tempfile.TemporaryDirectory() as tmp:
            directory=Path(tmp)/'datasets';save(directory,report,'report')
            self.assertEqual(load(directory/'report-index.json'),report)
            self.assertEqual(digest(load(directory/'report-index.json')),digest(report))

    def test_changed_shard_is_rejected_without_repair(self):
        report=dict(cases=[],calls=[dict(unitIndex=1)])
        with tempfile.TemporaryDirectory() as tmp:
            directory=Path(tmp)/'datasets';save(directory,report,'report')
            real_read=read
            def changed(path):
                value=real_read(path)
                if Path(path).name=='report-unit-001.json':value['unitIndex']=2
                return value
            with patch('private_report_bundle.read',changed),self.assertRaises(ValueError):load(directory/'report-index.json')

    def test_rehashed_traversal_name_is_rejected_before_loading_unit(self):
        report=dict(cases=[],calls=[dict(unitIndex=1)])
        with tempfile.TemporaryDirectory() as tmp:
            directory=Path(tmp)/'datasets';save(directory,report,'report')
            bundle=read(directory/'report-index.json');bundle['units'][0]['file']='../outside.json'
            bundle['bundleHash']=digest({k:v for k,v in bundle.items() if k!='bundleHash'})
            with patch('private_report_bundle.read',return_value=bundle),self.assertRaises(ValueError):load(directory/'report-index.json')
