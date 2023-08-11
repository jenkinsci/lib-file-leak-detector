/*
 * While this is not a plugin, it is much simpler to reuse the pipeline code for CI. This allows for
 * easy Linux/Windows testing and produces incrementals. The only feature that relates to plugins is
 * allowing one to test against multiple Jenkins versions.
 */
buildPlugin(useContainerAgent: true, configurations: [
  [ platform: 'windows', jdk: '17' ],
  [ platform: 'linux', jdk: '21' ]
])
