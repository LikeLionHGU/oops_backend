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
    default_interval_sec: float = 4.0

    # 영상 길이 상한.
    # 타깃이 20~60분 롱폼 토크·인터뷰인데, 실제로는 70~80분짜리도 흔해서
    # 90분까지 받는다.
    #
    # 다만 긴 영상은 비용과 시간이 크게 늘어난다.
    #   60분: 음성 인식만 약 500원, 대본 300줄 이상 → LLM 호출 수십 번
    #   90분: 그보다 1.5배
    # 요청 한도가 낮은 계정에서는 중간에 끊길 수 있다.
    # 시연은 3~5분짜리로 하는 것을 권한다.
    max_duration_sec: int = 5400

    # OCR 로 인식할 최대 프레임 수.
    #
    # 간격만 고정하면 영상이 길어질수록 프레임이 선형으로 늘어난다.
    # 4초 간격이면 60분 영상에 900장이고, 인식에만 7분 넘게 걸린다.
    # 그래서 상한을 두고, 넘으면 간격을 자동으로 늘린다.
    # 자막은 몇 초씩 유지되므로 간격이 벌어져도 대부분 잡힌다.
    max_ocr_frames: int = 300


@lru_cache
def get_settings() -> Settings:
    return Settings()
