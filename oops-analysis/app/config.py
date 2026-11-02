from functools import lru_cache
from typing import Literal

from pydantic_settings import BaseSettings, SettingsConfigDict
from pydantic import Field


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    openai_api_key: str = ""
    groq_api_key: str = ""
    # 지원 크레딧이 특정 조직에 붙어 있을 때만 채운다 (org-...)
    openai_org_id: str = ""
    openai_project_id: str = ""
    whisper_model: str = "whisper-large-v3"
    # 명시적 모델 재정의. 비어 있으면 기본 WHISPER_MODEL을 사용한다.
    stt_model: str | None = None
    work_dir: str = "./workdir"
    # Spring 의 storage.location 과 같은 디렉터리. 서버 배포 환경에서는 절대 경로로 지정한다.
    media_storage_root: str = "../oops-backend/uploads"
    media_timeout_sec: int = Field(default=300, gt=0, le=600)

    # 영상 길이 상한. (2026-11 redesign-v3: 90분 → 120분)
    # Groq Whisper 기본 한도가 시간당 음성 7,200초(=120분), 하루 28,800초다.
    # 120분 영상 하나가 한 시간 한도를 다 쓰므로 이보다 길게 받으면 전사 중간에 429 로 막힌다.
    # 더 늘리려면 Groq 한도를 올린 뒤 이 값을 올린다.
    #
    # 다만 긴 영상은 비용과 시간이 크게 늘어난다.
    #   60분: Groq 음성 인식만 약 155원(USD/KRW 1,400원 가정), 대본 300줄 이상 → LLM 호출 수십 번
    #   120분: 음성 인식만 약 310원
    # 요청 한도가 낮은 계정에서는 중간에 끊길 수 있다.
    # 시연은 3~5분짜리로 하는 것을 권한다.
    max_duration_sec: int = Field(default=7200, gt=0)
    # Explicit opt-in for a local worker. Default/deployed worker retains its configured limit.
    analysis_profile: Literal["default", "local"] = "default"

    @property
    def effective_max_duration_sec(self) -> int:
        """0 means unlimited only in the explicitly selected local profile."""
        return 0 if self.analysis_profile == "local" else self.max_duration_sec


@lru_cache
def get_settings() -> Settings:
    return Settings()
