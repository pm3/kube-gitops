package eu.aston.gitops.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.yaml.snakeyaml.nodes.AnchorNode;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

public class Yaml2GsonConverter {

    private JsonArray convertSequence(SequenceNode sequenceNode) {
        JsonArray array = new JsonArray();
        for (Node node : sequenceNode.getValue()) {
            array.add(convertAny(node));
        }
        return array;
    }

    private JsonObject convertMapping(MappingNode mappingNode) {
        JsonObject jsonObject = new JsonObject();
        for (NodeTuple tuple : mappingNode.getValue()) {
            Node keyNode = tuple.getKeyNode();
            String name = ((ScalarNode) keyNode).getValue();
            Node valueNode = tuple.getValueNode();
            jsonObject.add(name, convertAny(valueNode));
        }
        return jsonObject;
    }

    private JsonPrimitive convertScalar(ScalarNode scalarNode) {
        return new JsonPrimitive(scalarNode.getValue());
    }

    public JsonElement convertAny(Node node) {
        if(node instanceof ScalarNode scalarNode) return convertScalar(scalarNode);
        if(node instanceof MappingNode mappingNode) return convertMapping(mappingNode);
        if(node instanceof SequenceNode sequenceNode) return convertSequence(sequenceNode);
        if(node instanceof AnchorNode) throw new IllegalStateException("Anchor node type " + node);
        throw new IllegalStateException("Unknown node type " + node.getClass().getSimpleName());
    }
}