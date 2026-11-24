"""Cases for the document extraction commands the samples do not cover.

Extract with examples needs a model: the local stand-in's scripted chat model (an OpenAI-compatible address under
$standin_url$/v1). It answers JSON for a claim text marked CLAIM-SUGGEST (decision, approved_amount, reason), which is
what this case asks for. The API key is a made-up value from the vault.
"""
from helpers_office import fix_samples, fcase

KEY = {"$credential": "unit_tests/ai/api_key"}
FIELDS = {"decision": "approve or reject", "approved_amount": "the amount to pay", "reason": "why"}


def add(ua):
    fix_samples(ua)
    for n, t in (("vals", "Dictionary"), ("n", "Number"), ("v", "Any")):
        ua.var(n, t)

    with fcase(ua, "ooa.idp/extract_with_examples", "claim decision with one checked example",
               "Extract with examples asks a model for named fields and sends the latest checked examples of the same "
               "document type along; the case keeps one example, extracts from a new claim and checks the fields and "
               "that one example went with the request.") as (f, d):
        f.act("ooa.idp/save_example", desc="Keep a checked claim", examples_file=d + "/claims.examples.json",
              document_type="claim", text="CLAIM-SUGGEST Claimed amount: 450 Deductible: 50",
              fields={"decision": "approve", "approved_amount": 400, "reason": "within policy"}, out={"count": "n"})
        ua.check_eq("n", 1)
        f.act("ooa.idp/extract_with_examples", desc="Extract from a new claim",
              text="CLAIM-SUGGEST Claimed amount: 500 Deductible: 100", fields=FIELDS,
              examples_file=d + "/claims.examples.json", document_type="claim", provider="openai_compatible",
              base_url="${standin_url}/v1", api_key=KEY, model="qa-scripted",
              out={"fields": "vals", "examples_used": "n"})
        ua.check_eq("n", 1)
        f.expr("v", "$vals.decision", desc="The decision")
        ua.check_eq("v", "approve")
        f.expr("v", "$vals.approved_amount", desc="The amount")
        ua.check_eq("v", 400)

    with fcase(ua, "ooa.idp/save_example", "two examples of one type",
               "Save example keeps a document's checked values in the example file of its type and says how many "
               "examples that type now has.") as (f, d):
        for i, (num, total) in enumerate((("INV-1", "10.00"), ("INV-2", "20.00")), 1):
            f.act("ooa.idp/save_example", desc="Keep invoice " + num, examples_file=d + "/inv.examples.json",
                  document_type="invoice", text="Invoice " + num + " Total " + total,
                  fields={"invoice_number": num, "total": total}, out={"count": "n"})
            ua.check_eq("n", i)
        f.act("ooa.file/read_text", desc="Read the example file", path=d + "/inv.examples.json", out={"text": "v"})
        ua.check("contains", "$v$", "INV-2", what="the example file")
