package eu.aston.gitops.model;

public record GitData(String url,
                      String user,
                      String password,
                      String sshKey,
                      String branch){}
