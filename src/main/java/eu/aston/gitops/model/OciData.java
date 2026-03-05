package eu.aston.gitops.model;

import com.google.gson.annotations.SerializedName;

public record OciData(@SerializedName("name") String img, String auth) {
}
