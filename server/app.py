from fastapi import FastAPI
from fastapi.responses import StreamingResponse
from pydantic import BaseModel
from strands import Agent
from strands.models.gemini import GeminiModel
import asyncio
import json

app = FastAPI()

class ChatRequest(BaseModel):
    text: str

gemini_model = GeminiModel(
    client_args={"api_key": "AIzaSyAg4JKigMQPBzbDvz2hLNUWCCruze5HhhI"},
    model_id="gemini-2.5-flash",
    params={"temperature": 0.7}
)

agent = Agent(model=gemini_model)

# ── SSE generator ─────────────────────────
async def event_generator(text: str):
    try:
        # ⚠️ run blocking LLM in thread (IMPORTANT)
        with open("td.txt", "w") as f:
            f.write(text)
        text = "tell me lion story in 2 lines,with good markdown texts, headings, emojis etc and many morea"
        loop = asyncio.get_event_loop()
        output_data = agent(text)
        inputTokens = output_data.metrics.accumulated_usage["inputTokens"]
        outputTokens = output_data.metrics.accumulated_usage["outputTokens"]
        totalTokens = output_data.metrics.accumulated_usage["totalTokens"]
        print(inputTokens, outputTokens, totalTokens)
        response_text = await loop.run_in_executor(None, lambda: str(output_data))
        yield f"data: {json.dumps({'text': str(response_text)})}\n\n"
        await asyncio.sleep(0.01)
        yield f"data: {json.dumps({'done': True, 'inputTokens': inputTokens, 'outputTokens': outputTokens, 'totalTokens': totalTokens})}\n\n"

    except Exception as e:
        yield f"data: {json.dumps({'error': str(e)})}\n\n"

# ── Endpoint ─────────────────────────
@app.post("/chat-stream")
async def chat_stream(req: ChatRequest):
    return StreamingResponse(
        event_generator(req.text),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
        }
    )

# uvicorn app:app --reload --port 8003 --host 0.0.0.0

        # # stream word by word
        # for word in response_text.split():
        #     chunk = word + " "
        #     yield f"data: {json.dumps({'text': chunk})}\n\n"
        #     await asyncio.sleep(0.02)
