package com.mindflow.module.wiki.dto;

import java.util.ArrayList;
import java.util.List;

public class PartialWikiEntry {

    private String name;
    private String definition;
    private List<String> keyInfo = new ArrayList<>();
    private List<String> relatedConcepts = new ArrayList<>();
    private List<String> aliases = new ArrayList<>();
    private String sourceChunkId;

    public PartialWikiEntry() {}

    public PartialWikiEntry(String name, String definition, List<String> keyInfo,
                            List<String> relatedConcepts, String sourceChunkId) {
        this.name = name;
        this.definition = definition;
        this.keyInfo = keyInfo;
        this.relatedConcepts = relatedConcepts;
        this.sourceChunkId = sourceChunkId;
    }

    // getters and setters
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDefinition() { return definition; }
    public void setDefinition(String definition) { this.definition = definition; }
    public List<String> getKeyInfo() { return keyInfo; }
    public void setKeyInfo(List<String> keyInfo) { this.keyInfo = keyInfo; }
    public List<String> getRelatedConcepts() { return relatedConcepts; }
    public void setRelatedConcepts(List<String> relatedConcepts) { this.relatedConcepts = relatedConcepts; }
    public List<String> getAliases() { return aliases; }
    public void setAliases(List<String> aliases) { this.aliases = aliases; }
    public String getSourceChunkId() { return sourceChunkId; }
    public void setSourceChunkId(String sourceChunkId) { this.sourceChunkId = sourceChunkId; }
}
