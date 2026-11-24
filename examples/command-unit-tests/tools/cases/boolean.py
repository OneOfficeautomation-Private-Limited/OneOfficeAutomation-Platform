"""Cases for the Boolean group: the samples (fuller converter) and the two conditions."""
from helpers_core import resample


def add(ua):
    resample(ua)
    ua.condition_case("ooa.boolean/is_true", "yes counts as true",
                      "Is true is a condition: true for Yes values such as true, yes or 1.", {"value": "yes"}, True)
    ua.condition_case("ooa.boolean/is_true", "a no-word is not true",
                      "Is true is false for a no-word such as no or false.", {"value": "no"}, False)
    ua.condition_case("ooa.boolean/is_false", "0 counts as false",
                      "Is false is a condition: true for No values such as false, no, 0 or empty.", {"value": 0}, True)
    ua.condition_case("ooa.boolean/is_false", "true is not false",
                      "Is false is false for a Yes value.", {"value": True}, False)
