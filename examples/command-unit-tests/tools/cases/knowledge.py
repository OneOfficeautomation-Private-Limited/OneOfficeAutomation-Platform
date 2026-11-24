"""Hand-written cases for the Knowledge Q&A commands: the index is built from files and texts in the run folder, with
embeddings from the stand-in (OpenAI-compatible, $standin_url$/v1/embeddings); answers come from its scripted model."""
from helpers_cloud import adapt_samples, value, cred

T = "ooa.knowledge/"
MODEL = {"provider": "openai_compatible", "base_url": "$standin_url$/v1", "api_key": cred("api/token")}
INDEX = "knowledge/policies.index.json"


def _build(f):
    f.act("ooa.file/write_text", desc="A refunds policy", path="knowledge/docs/refunds.txt",
          text="Refunds policy. Customers may return goods within 30 days of delivery for a full refund. "
               "Damaged items are refunded without the return shipping cost.")
    f.act(T + "build_index", desc="Index the policy and a travel rule", files=["knowledge/docs"],
          texts=[{"source": "travel-rule", "text": "Travel expenses. Hotel nights are paid up to 150 euros in Germany."}],
          index_file=INDEX, model="ut-embed", chunk_size=400, overlap=50, out={"passages": "kn_passages", "documents": "kn_docs"},
          **MODEL)


def add(ua):
    adapt_samples(ua, ua.f.name)
    for n, t in (("kn_passages", "Number"), ("kn_docs", "Number"), ("kn_results", "List"), ("kn_answer", "String"),
                 ("kn_sources", "List"), ("kn_found", "Boolean")):
        ua.var(n, t)

    with ua.case(T + "build_index", "a folder and a text, with embeddings",
                 "Build index reads documents and texts, splits them into passages and writes a knowledge index file, "
                 "with embeddings from the model given. Checked: two documents and at least two passages.") as f:
        _build(f)
        ua.check_eq("kn_docs", 2)
        ua.check("ge", "$kn_passages$", 2, what="passages")

    with ua.case(T + "search", "the passage about refunds",
                 "Search finds the passages that best answer a question, by words and by meaning. Checked: the best "
                 "passage comes from the refunds policy.") as f:
        _build(f)
        f.act(T + "search", desc="Look for the refund rule", index_file=INDEX, query="How many days to return goods for a refund?",
              top_k=2, api_key=cred("api/token"), out={"results": "kn_results"})
        value(ua, "$kn_results[0].source", "endsWith", "refunds.txt", "the best passage's source")

    with ua.case(T + "ask", "an answer from the passages",
                 "Ask gives the best passages to the model and asks it to answer from them only, citing them. Checked: "
                 "found, the sources and the model's answer.") as f:
        _build(f)
        f.act(T + "ask", desc="Ask about hotel costs", index_file=INDEX, question="How much is paid for a hotel night in Germany?",
              top_k=3, model="qa-scripted", max_tokens=200, mask_pii=True, out={"answer": "kn_answer", "sources": "kn_sources", "found": "kn_found"},
              **MODEL)
        ua.check_eq("kn_found", True)
        ua.check("notEmpty", "$kn_sources$", what="the sources")
        ua.check("notEmpty", "$kn_answer$", what="the answer")

    with ua.case(T + "ask", "nothing in the documents",
                 "When no passage matches the question, Ask does not call the model and says the documents do not say. "
                 "The index here is built without embeddings, so it is searched by words only. Checked: found is false.") as f:
        f.act(T + "build_index", desc="Index a text by words only", texts=[{"source": "travel-rule", "text": "Hotel nights are paid up to 150 euros."}],
              index_file="knowledge/words.index.json")
        f.act(T + "ask", desc="Ask something unrelated", index_file="knowledge/words.index.json", question="Zebra xylophone quantum?", model="qa-scripted",
              out={"answer": "kn_answer", "found": "kn_found"}, **MODEL)
        ua.check_eq("kn_found", False)
        ua.check_eq("kn_answer", "The documents do not say.")
