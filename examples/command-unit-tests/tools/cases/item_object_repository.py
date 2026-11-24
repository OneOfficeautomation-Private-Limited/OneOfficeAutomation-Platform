"""UT Item Object repository: fills in the stand-in's UT Shop sign-in page (/plat-app/login) in a headless browser,
finding every element through the sample repository UT Shop.objects.json (by properties, CSS and XPath)."""
from helpers_platform import renumber, items_input, copy_item

B = "ooa.browser/"
REPO = "ut-items/UT Shop.objects.json"


def obj(element):
    return {"repository": REPO, "element": "UT Shop web/Login/" + element}


def add(ua):
    items_input(ua)
    for v, t in (("or_text", "String"), ("or_attr", "String")):
        ua.var(v, t)
    f = ua.f
    with ua.case(B + "open", "open the page in a headless browser",
                 "The sample repository is copied into the work folder (a repository must be inside the workspace) "
                 "and the page is opened without a window; the heading is found through the repository."):
        copy_item(f, "object-repository/UT Shop.objects.json", REPO)
        f.act(B + "open", url="${standin_url}/plat-app/login", headless=True, session="ut_shop")
        f.act(B + "get_text", object=obj("Heading"), session="ut_shop", out={"text": "or_text"})
        ua.check_eq("or_text", "UT Shop")
    with ua.case(B + "type_text", "fill in the fields found by the repository",
                 "Type text and Select option use the repository's User name (tag and id), Password (tag and name) "
                 "and Country (drop-down) elements; the typed name is read back as the field's value."):
        f.act(B + "type_text", object=obj("User name"), text="ut_robot", session="ut_shop")
        f.act(B + "type_text", object=obj("Password"), text="made-up-pass", session="ut_shop")
        f.act(B + "select_option", object=obj("Country"), option="IN", session="ut_shop")
        f.act(B + "get_attribute", object=obj("User name"), attribute="value", session="ut_shop", out={"value": "or_attr"})
        ua.check_eq("or_attr", "ut_robot")
    with ua.case(B + "click", "sign in and read the message",
                 "Click uses the Sign in element (a CSS selector); Get text uses the Message element (an XPath): it "
                 "says Welcome, ut_robot (IN). The browser is closed afterwards."):
        f.act(B + "click", object=obj("Sign in"), session="ut_shop")
        f.act(B + "get_text", object=obj("Message"), session="ut_shop", out={"text": "or_text"})
        f.act(B + "close", session="ut_shop")
        ua.check_eq("or_text", "Welcome, ut_robot (IN)")
    renumber(ua)
