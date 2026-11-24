"""UT Item Document type: reads a made-up invoice with the fields of the sample document type UT Invoice.doc.json, the
way the extraction agent Studio makes from a document type does: the fields (with their types and whether they are
required) become the field descriptions of Extract fields. The model is the stand-in (/v1 of the stand-in)."""
from helpers_platform import renumber, items_input, copy_item, ai_key, make_pdf, INVOICE_LINES

DOC = "ut-items/UT Invoice.doc.json"
PDF = "ut-items/ut-invoice.pdf"
LINES = ["Supplier: UT Supplies Ltd"] + INVOICE_LINES[1:]


def add(ua):
    items_input(ua)
    ai_key(ua)
    for v, t in (("dt_type", "Dictionary"), ("dt_fields", "Dictionary"), ("dt_field", "Dictionary"), ("dt_desc", "String"),
                 ("dt_text", "String"), ("dt_values", "Dictionary"), ("dt_value", "Any"), ("dt_accept", "List"),
                 ("dt_conf", "Dictionary")):
        ua.var(v, t)
    f = ua.f

    def read_type():
        copy_item(f, "document-type/UT Invoice.doc.json", DOC)
        f.act("ooa.json/read_file", desc="Read the document type", path=DOC, out={"value": "dt_type"})

    with ua.case("ooa.json/read_file", "the document type's settings",
                 "The sample document type accepts PDF and text files, uses the model ut-model and has six fields."):
        read_type()
        f.expr("dt_accept", "$dt_type.accept")
        ua.check("contains", "$dt_accept$", "pdf", what="accepted files")
        f.expr("dt_desc", "$dt_type.model")
        ua.check_eq("dt_desc", "ut-model")
        f.expr("dt_value", "len($dt_type.fields)")
        ua.check("eq", "$dt_value$", 6, what="fields")
    with ua.case("ooa.genai/extract", "read the invoice with the document type's fields",
                 "Each field of the document type becomes a description for Extract fields (its type in brackets "
                 "when not text, may be missing when not required); the invoice PDF's text goes to the model, and "
                 "every field comes back with the invoice's value."):
        read_type()
        f.set("dt_fields", {}, desc="Start with no field descriptions")
        with f.loop(desc="One description per field of the document type", iterator="list", over="$dt_type.fields$",
                    **{"as": "dt_field"}):
            f.set("dt_desc", "${dt_field.description}")
            with f.if_({"op": "ne", "left": "$dt_field.type$", "right": "String"}, desc="Not text: say the type"):
                f.set("dt_desc", "${dt_desc} (${dt_field.type})")
            with f.if_({"op": "ne", "left": "$dt_field.required$", "right": True}, desc="Not required"):
                f.set("dt_desc", "${dt_desc} (may be missing)")
            f.act("ooa.dictionary/put", dictionary="$dt_fields$", key="$dt_field.name$", value="$dt_desc$",
                  out={"dictionary": "dt_fields"})
        f.act("ooa.file/write_text", desc="Write the made-up invoice PDF", path=PDF, text=make_pdf(LINES))
        f.act("ooa.pdf/extract_text", path=PDF, out={"text": "dt_text"})
        f.act("ooa.genai/extract", text="$dt_text$", fields="$dt_fields$", provider="openai_compatible",
              model="$dt_type.model$", base_url="${standin_url}/v1", api_key="$ai_key$",
              out={"fields": "dt_values"})
        for k, want in (("supplier", "UT Supplies Ltd"), ("invoice_number", "INV-UT-1"), ("po_number", "PO-4711"),
                        ("currency", "EUR")):
            f.expr("dt_value", "$dt_values." + k)
            ua.check("eq", "$dt_value$", want, what=k)
        f.expr("dt_value", "$dt_values.total")
        ua.check("eq", "$dt_value$", 120.5, what="total")
        f.expr("dt_value", "$dt_values.invoice_date")
        ua.check("contains", "$dt_value$", "2026-11-20", what="invoice_date")
    renumber(ua)
