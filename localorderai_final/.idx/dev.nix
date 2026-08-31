{ pkgs, ... }: {
  channel = "stable-24.05";

  packages = [
    pkgs.jdk17
  ];

  env = {};

  idx = {
    extensions = [
      "redhat.java"
    ];

    android = {
      enable = true;
    };

    previews = {
      enable = true;
      previews = {
        android = {
          manager = "android";
        };
      };
    };
  };
}
