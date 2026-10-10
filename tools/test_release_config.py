import unittest

from release_config import release_template, validate_version


class ReleaseConfigTest(unittest.TestCase):
    def test_valid_versions(self):
        for version in ["0.1.0", "1.2.3-rc.1", "1.2.3+build.4"]:
            validate_version(version)

    def test_invalid_or_snapshot_versions(self):
        for version in ["", "01.2.3", "1.2", "1.2.3-SNAPSHOT", "1.2.3-rc.SNAPSHOT", "1.2.3\nsettings:", "$(touch bad)"]:
            with self.assertRaises(ValueError):
                validate_version(version)

    def test_template_uses_native_inheritance_and_manual_approval(self):
        template = release_template("0.1.0", "./base.module-template.yaml")
        self.assertIn('version: "0.1.0"', template)
        self.assertIn('"./base.module-template.yaml"', template)
        self.assertIn("publishingMode: manual", template)
        self.assertIn("signArtifacts: true", template)

    def test_auto_publication_requires_explicit_selection(self):
        self.assertIn("publishingMode: auto", release_template("0.1.0", "./base.module-template.yaml", "auto"))
        with self.assertRaises(ValueError):
            release_template("0.1.0", "./base.module-template.yaml", "unreviewed")


if __name__ == "__main__":
    unittest.main()
