"""Hand-written cases for the Generative AI commands: the stand-in's scripted model is an OpenAI-compatible provider at
$standin_url$/v1; Hugging Face and SageMaker endpoints are at $standin_url$/cloud-genai."""
from helpers_cloud import adapt_samples, value, cred

T = "ooa.genai/"
MODEL = {"provider": "openai_compatible", "base_url": "$standin_url$/v1", "api_key": cred("api/token"), "model": "qa-scripted"}


def add(ua):
    adapt_samples(ua, ua.f.name)
    for n, t in (("ai_text", "String"), ("ai_turns", "Number"), ("ai_json", "Any"), ("ai_type", "String"), ("ai_file", "String"),
                 ("ai_variant", "String"), ("ai_fields", "Dictionary")):
        ua.var(n, t)

    with ua.case(T + "prompt", "the stand-in's scripted model",
                 "Prompt sends one prompt to a model; here an OpenAI-compatible provider (the stand-in's scripted model, "
                 "made-up key from the vault). Checked: the model's answer comes back as text.") as f:
        f.act(T + "prompt", desc="Ask the scripted model", prompt="Say hello to the unit tests.", temperature=0, max_tokens=50,
              out={"text": "ai_text"}, **MODEL)
        ua.check("contains", "$ai_text$", "scripted model", what="the answer")

    with ua.case(T + "extract", "fields from labelled lines",
                 "Extract asks the model for named fields from a text; the scripted model reads labelled lines. Checked: "
                 "the supplier and the total.") as f:
        f.act(T + "extract", desc="Fields from an invoice text", text="Supplier: Acme GmbH\nInvoice number: INV-77\nTotal: 120.50",
              fields={"supplier": "text", "invoice_number": "text", "total": "number"}, out={"fields": "ai_fields"}, **MODEL)
        value(ua, "$ai_fields.supplier", "eq", "Acme GmbH", "the supplier")
        value(ua, "$ai_fields.invoice_number", "eq", "INV-77", "the invoice number")

    with ua.case(T + "end_chat", "start the conversation again",
                 "End chat forgets the messages of a chat session, so the next Chat starts afresh. Checked: after two "
                 "turns the session holds 4 messages; after End chat a new message leaves 2.") as f:
        f.act(T + "chat", desc="First message", chat_session="ut-chat", message="My name is Ann.", **MODEL)
        f.act(T + "chat", desc="Second message", chat_session="ut-chat", message="What is my name?", out={"turns": "ai_turns"}, **MODEL)
        ua.check_eq("ai_turns", 4)
        f.act(T + "end_chat", desc="End the conversation", chat_session="ut-chat")
        f.act(T + "chat", desc="A new conversation", chat_session="ut-chat", message="Hello again.", out={"turns": "ai_turns"}, **MODEL)
        ua.check_eq("ai_turns", 2)
        f.act(T + "end_chat", desc="Tidy up", chat_session="ut-chat")

    with ua.case(T + "huggingface_inference", "a JSON payload to a text model",
                 "Hugging Face model calls a model for any task with a JSON payload (here on the stand-in's inference "
                 "server). Checked: the label in the JSON answer.") as f:
        f.act(T + "huggingface_inference", desc="Sentiment of a complaint", model="ut/sentiment",
              payload='{"inputs": "The parcel was late and the box was broken."}', api_key=cred("api/token"),
              base_url="$standin_url$/cloud-genai/hf", timeout_seconds=30, out={"json": "ai_json", "content_type": "ai_type"})
        value(ua, "$ai_json[0][0].label", "eq", "NEGATIVE", "the top label")
        ua.check("contains", "$ai_type$", "json", what="the content type")

    with ua.case(T + "huggingface_inference", "a picture saved to a file",
                 "When the model answers with a picture, Hugging Face model saves it to output_file. Checked: the file "
                 "and its content type.") as f:
        f.act(T + "huggingface_inference", desc="Draw a picture", model="ut/text-to-image", payload='{"inputs": "a blue square"}',
              base_url="$standin_url$/cloud-genai/hf", output_file="genai/picture.png", out={"file": "ai_file", "content_type": "ai_type"})
        ua.check("endsWith", "$ai_file$", "picture.png", what="the saved file")
        ua.check_eq("ai_type", "image/png")

    with ua.case(T + "invoke_endpoint", "a SageMaker model endpoint",
                 "Invoke endpoint sends a request body to an Amazon SageMaker endpoint (signed with AWS keys from the "
                 "vault) and returns its answer. Checked: the prediction and the variant that answered.") as f:
        f.act(T + "invoke_endpoint", desc="Score one customer", endpoint_name="ut-churn-model", body='{"instances": [[1.5, 2, 0]]}',
              content_type="application/json", accept="application/json", aws_access_key_id=cred("aws/id"), api_key=cred("aws/secret"),
              aws_region="eu-west-1", base_url="$standin_url$/cloud-genai/sagemaker", inference_id="ut-1",
              out={"json": "ai_json", "invoked_variant": "ai_variant"})
        value(ua, "$ai_json.predictions[0]", "eq", 0.45, "the prediction")
        ua.check_eq("ai_variant", "AllTraffic")
