from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    openai_api_key: str = ""
    # 지원 크레딧이 특정 조직에 붙어 있을 때만 채운다 (org-...)
    openai_org_id: str = ""
    openai_project_id: str = ""
    whisper_model: str = "whisper-1"
    work_dir: str = "./workdir"
    ocr_lang: str = "korean"

    # 프레임을 몇 초 간격으로 뽑을지 (OCR 기본값)
    default_interval_sec: float = 2.0
    # 영상이 길면 비용/시간이 폭발하므로 상한을 둔다
    max_duration_sec: int = 1800


@lru_cache
def get_settings() -> Settings:
    return Settings()
