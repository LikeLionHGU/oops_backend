import json
from pathlib import Path
import shutil
import subprocess
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient
from app import main, media


@pytest.mark.parametrize("value", ["0", "-1", "NaN", "Infinity", "missing", None])
def test_probe_unknown_duration_is_failure_not_zero(monkeypatch,value):
    monkeypatch.setattr(media.subprocess,"run",lambda *a,**k: SimpleNamespace(
        returncode=0,stdout=json.dumps({"format":{"duration":value}})))
    with pytest.raises(media.MediaError):media.probe_duration(Path("fixture"))


def test_probe_nonzero_exit_is_failure(monkeypatch):
    monkeypatch.setattr(media.subprocess,"run",lambda *a,**k:SimpleNamespace(returncode=1))
    with pytest.raises(media.MediaError):media.probe_duration(Path("fixture"))


def test_valid_probe_preserves_fractional_duration_and_bounded_command(monkeypatch):
    def run(command,**kw):
        assert kw['timeout']==20
        return SimpleNamespace(returncode=0,stdout='{"format":{"duration":"10.75"}}')
    monkeypatch.setattr(media.subprocess,'run',run)
    assert media.probe_duration(Path('fixture'))==10.75


def test_command_timeout_is_controlled_media_error(monkeypatch):
    def run(command,**kw):raise subprocess.TimeoutExpired(command,kw['timeout'])
    monkeypatch.setattr(media.subprocess,'run',run)
    with pytest.raises(media.MediaError,match='제한 시간'):
        media.run_command(['ffmpeg'],timeout=20,label='fixture')


@pytest.mark.parametrize('value',[0,-1,float('nan'),float('inf')])
def test_duration_guard_rejects_unknown_nonfinite_and_nonpositive(value):
    with pytest.raises(media.MediaError):main._guard_duration(value)


@pytest.mark.parametrize('value',[0,-1])
def test_invalid_ocr_interval_is_rejected_before_processing(monkeypatch,value):
    monkeypatch.setattr(main,'prepare',lambda *a:pytest.fail('media prepared'))
    assert TestClient(main.app).post('/ocr',json={'filePath':'fixture','intervalSec':value}).status_code==422


def test_ocr_uses_actual_pts_not_frame_number(tmp_path,monkeypatch):
    video=media.PreparedVideo(tmp_path/'source',tmp_path,10)
    monkeypatch.setattr(media,'get_settings',lambda:SimpleNamespace(media_timeout_sec=300))
    def run(command,**kw):
        assert kw['timeout']==300
        assert 'select=' in command[command.index('-vf')+1]
        assert 'fps=1/' not in command[command.index('-vf')+1]
        assert command[command.index('-fps_mode')+1]=='vfr'
        (tmp_path/'frames/frame_00001.jpg').touch()
        (tmp_path/'frames/frame_00002.jpg').touch()
        return SimpleNamespace(returncode=0,stderr='[Parsed_showinfo_2 @ x] n: 0 pts_time:0\n'
                                                '[Parsed_showinfo_2 @ x] n: 1 pts_time:4.12\n')
    monkeypatch.setattr(media.subprocess,'run',run)
    assert [t for t,p in media.extract_frames(video,4)]==[0,4120]


def test_missing_pts_is_not_fabricated(tmp_path,monkeypatch):
    video=media.PreparedVideo(tmp_path/'source',tmp_path,10)
    def run(command,**kw):
        (tmp_path/'frames/frame_00001.jpg').touch()
        return SimpleNamespace(returncode=0,stderr='no timestamp metadata')
    monkeypatch.setattr(media.subprocess,'run',run)
    with pytest.raises(media.MediaError,match='시간 정보'):media.extract_frames(video,4)


@pytest.mark.skipif(shutil.which('ffmpeg') is None,reason='local ffmpeg required')
def test_real_ffmpeg_samples_source_zero_four_eight(tmp_path):
    source=tmp_path/'source.mp4'
    subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-f','lavfi','-i',
                    'testsrc=size=64x64:rate=25:duration=9','-c:v','mpeg4',str(source)],
                   check=True,capture_output=True,timeout=20)
    video=media.PreparedVideo(source,tmp_path,9)
    assert [t for t,p in media.extract_frames(video,4)]==[0,4000,8000]
