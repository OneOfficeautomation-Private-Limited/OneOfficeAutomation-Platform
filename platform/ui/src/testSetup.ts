import { configure } from "@testing-library/react";

// findBy* and waitFor wait up to 5 s for a screen to appear (the library's default is 1 s). The UI tests run on CI's
// Windows machines next to the whole Java build, where a console page can take several seconds to render; a test that
// passes still finishes as soon as the screen is there.
configure({ asyncUtilTimeout: 5000 });
