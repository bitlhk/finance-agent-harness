"""Windows Web 到 WSL 回环 Worker 的最小受限客户端。"""

import os

from ..contracts import BusinessError


class WorkerRanker:
    def __init__(self, config):
        self.config = config

    async def __call__(self, context):
        import httpx

        token = os.environ.get(self.config.worker_token_env, "")
        if not token or any(x in token for x in ("\n","\r")):
            raise BusinessError("worker-configuration-unavailable")
        try:
            async with httpx.AsyncClient(follow_redirects=False,trust_env=False,
                                        timeout=self.config.seconds+1) as client:
                response = await client.post(self.config.worker_url.rstrip('/')+'/rank',
                    headers={"Authorization":"Bearer "+token}, json={"context":context.model_dump(mode="json")})
        except httpx.TimeoutException:
            raise BusinessError('worker-timeout') from None
        except httpx.TransportError:
            raise BusinessError('worker-unavailable') from None
        if response.status_code != 200:
            raise BusinessError("worker-busy" if response.status_code == 409 else "worker-unavailable")
        if len(response.content) > 1024*1024:
            raise BusinessError("worker-response-too-large")
        data = response.json()
        return data['ranking'], data['provenance'], data['calls']
