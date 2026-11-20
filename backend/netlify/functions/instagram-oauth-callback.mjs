import { createRequire } from "node:module";
import { withLambda } from "@netlify/aws-lambda-compat";

const require = createRequire(import.meta.url);
const { callbackHandler } = require("../legacy-handlers/instagram-oauth.js");

export default withLambda(callbackHandler);
