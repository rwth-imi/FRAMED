from typing import Dict

import streamlit as st

from config_utils import find_implementations, find_all_classes_in_dirs

st.set_page_config(page_title="Factory Config Builder", layout="wide")


CLASS_DIRS = [
  "target/classes",
]

type_specs: Dict[str, str] = {
  "Parser": "com.framed.communicator.driver.parser.Parser",   # edit to your abstract class names
  "Writer": "com.framed.communicator.io.Writer",
  "Protocol": "com.framed.communicator.driver.protocol.Protocol",
  "Dispatcher": "com.framed.streamer.dispatcher.Dispatcher"
}

OUTPUT_CONFIG_PATH = "./services.json"

MVN_CMD = ["mvn", "exec:java"]

all_classes = find_all_classes_in_dirs(CLASS_DIRS, )










st.title("FRAMED Configurator")
for type_spec in type_specs:
  st.header(type_spec)
  implementations = find_implementations()
  st.selectbox()
